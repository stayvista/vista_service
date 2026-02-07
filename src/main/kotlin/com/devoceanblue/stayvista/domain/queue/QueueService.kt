package com.devoceanblue.stayvista.domain.queue

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service

@Service
class QueueService(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    @Value("\${stayvista.queue.max-admitted-per-key:100}") private val maxAdmittedPerKey: Int,
    @Value("\${stayvista.queue.ticket-ttl-seconds:1800}") private val ticketTtlSeconds: Long,
    @Value("\${stayvista.queue.admit-token-ttl-seconds:30}") private val admitTokenTtlSeconds: Long,
    @Value("\${stayvista.queue.secret:stayvista-queue-secret}") private val secret: String,
) {
    private val popAndAdmitScript = DefaultRedisScript<String>().apply {
        setScriptText(
            """
            local queue_key = KEYS[1]
            local admitted_key = KEYS[2]
            local max_admitted = tonumber(ARGV[1])
            local admitted_expiry = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])

            redis.call('ZREMRANGEBYSCORE', admitted_key, '-inf', now_ms)
            local admitted_count = redis.call('ZCARD', admitted_key)
            if admitted_count >= max_admitted then
              return ''
            end

            local popped = redis.call('ZPOPMIN', queue_key, 1)
            if popped == nil or #popped == 0 then
              return ''
            end

            local ticket_id = popped[1]
            redis.call('ZADD', admitted_key, admitted_expiry, ticket_id)
            return ticket_id
        """.trimIndent(),
        )
        setResultType(String::class.java)
    }

    fun join(queueKey: String, subject: String): QueueJoinData {
        val now = Instant.now(clock)
        val dedupeKey = joinedKey(queueKey, subject)
        val existingTicketId = redisTemplate.opsForValue().get(dedupeKey)

        if (!existingTicketId.isNullOrBlank() && isTicketAlive(existingTicketId, now)) {
            val position = queuePosition(queueKey, existingTicketId)
            return QueueJoinData(
                queue_key = queueKey,
                ticket = existingTicketId,
                position = if (position < 0) 0 else position,
                estimated_wait_seconds = estimateWaitSeconds(position),
            )
        }

        val ticketId = "qtk_${UUID.randomUUID()}"
        val expiresAt = now.plusSeconds(ticketTtlSeconds)

        val inserted = redisTemplate.opsForValue().setIfAbsent(dedupeKey, ticketId, Duration.ofSeconds(ticketTtlSeconds)) == true
        if (!inserted) {
            val dedupedTicketId = redisTemplate.opsForValue().get(dedupeKey)
            if (!dedupedTicketId.isNullOrBlank() && isTicketAlive(dedupedTicketId, now)) {
                val position = queuePosition(queueKey, dedupedTicketId)
                return QueueJoinData(
                    queue_key = queueKey,
                    ticket = dedupedTicketId,
                    position = if (position < 0) 0 else position,
                    estimated_wait_seconds = estimateWaitSeconds(position),
                )
            }
        }

        val ticketHashKey = ticketKey(ticketId)
        redisTemplate.opsForHash<String, String>().putAll(
            ticketHashKey,
            mapOf(
                "queue_key" to queueKey,
                "subject" to subject,
                "issued_at" to now.epochSecond.toString(),
                "expires_at" to expiresAt.epochSecond.toString(),
            ),
        )
        redisTemplate.expire(ticketHashKey, Duration.ofSeconds(ticketTtlSeconds))
        redisTemplate.opsForZSet().add(waitingQueueKey(queueKey), ticketId, now.toEpochMilli().toDouble())

        meterRegistry.counter("queue_join_total").increment()

        val position = queuePosition(queueKey, ticketId)
        return QueueJoinData(
            queue_key = queueKey,
            ticket = ticketId,
            position = if (position < 0) 0 else position,
            estimated_wait_seconds = estimateWaitSeconds(position),
        )
    }

    fun status(ticketId: String): QueueStatusData {
        val now = Instant.now(clock)
        val ticket = readTicket(ticketId) ?: return QueueStatusData(
            state = "EXPIRED",
            position = 0,
            estimated_wait_seconds = 0,
            admit_token = null,
        )

        if (ticket.expiresAt.isBefore(now)) {
            return QueueStatusData(
                state = "EXPIRED",
                position = 0,
                estimated_wait_seconds = 0,
                admit_token = null,
            )
        }

        admit(ticket.queueKey)

        val admittedUntilMs = redisTemplate.opsForZSet().score(admittedKey(ticket.queueKey), ticket.id)
        if (admittedUntilMs != null && admittedUntilMs.toLong() > now.toEpochMilli()) {
            return QueueStatusData(
                state = "ADMITTED",
                position = 0,
                estimated_wait_seconds = 0,
                admit_token = createAdmitToken(ticket.queueKey, ticket.id, now.plusSeconds(admitTokenTtlSeconds)),
            )
        }

        val position = queuePosition(ticket.queueKey, ticketId)
        return QueueStatusData(
            state = "WAITING",
            position = if (position < 0) 0 else position,
            estimated_wait_seconds = estimateWaitSeconds(position),
            admit_token = null,
        )
    }

    fun validateAdmitToken(token: String): Boolean {
        val chunks = token.split(".")
        if (chunks.size != 2) return false

        val payload = chunks[0]
        val signature = chunks[1]
        if (hmac(payload) != signature) return false

        val decoded = String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
        val parts = decoded.split("|")
        if (parts.size != 4) return false

        val queueKey = parts[0]
        val ticketId = parts[1]
        val expiresAt = Instant.ofEpochSecond(parts[2].toLongOrNull() ?: return false)
        if (expiresAt.isBefore(Instant.now(clock))) return false

        val admittedUntilMs = redisTemplate.opsForZSet().score(admittedKey(queueKey), ticketId) ?: return false
        return admittedUntilMs.toLong() > Instant.now(clock).toEpochMilli()
    }

    fun requireValidAdmitToken(token: String?) {
        if (token.isNullOrBlank() || !validateAdmitToken(token)) {
            throw DomainException(ErrorCode.QUEUE_REQUIRED, "Queue admit token is required")
        }
    }

    private fun admit(queueKey: String) {
        val nowMs = Instant.now(clock).toEpochMilli()
        while (true) {
            val poppedTicketId = redisTemplate.execute(
                popAndAdmitScript,
                listOf(waitingQueueKey(queueKey), admittedKey(queueKey)),
                maxAdmittedPerKey.toString(),
                (nowMs + admitTokenTtlSeconds * 1000).toString(),
                nowMs.toString(),
            )
            if (poppedTicketId.isNullOrBlank()) {
                break
            }
            meterRegistry.counter("queue_admitted_total").increment()
        }
    }

    private fun queuePosition(queueKey: String, ticketId: String): Int {
        val rank = redisTemplate.opsForZSet().rank(waitingQueueKey(queueKey), ticketId) ?: return -1
        return rank.toInt()
    }

    private fun estimateWaitSeconds(position: Int): Int {
        if (position <= 0) return 0
        return ((position / maxAdmittedPerKey.toDouble()) * admitTokenTtlSeconds).toInt().coerceAtLeast(1)
    }

    private fun isTicketAlive(ticketId: String, now: Instant): Boolean {
        val ticket = readTicket(ticketId) ?: return false
        return ticket.expiresAt.isAfter(now)
    }

    private fun readTicket(ticketId: String): QueueTicket? {
        val entries = redisTemplate.opsForHash<String, String>().entries(ticketKey(ticketId))
        if (entries.isEmpty()) return null

        val queueKey = entries["queue_key"] ?: return null
        val subject = entries["subject"] ?: "unknown"
        val issuedAt = Instant.ofEpochSecond(entries["issued_at"]?.toLongOrNull() ?: return null)
        val expiresAt = Instant.ofEpochSecond(entries["expires_at"]?.toLongOrNull() ?: return null)

        return QueueTicket(
            id = ticketId,
            queueKey = queueKey,
            subject = subject,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
        )
    }

    private fun waitingQueueKey(queueKey: String): String = "queue:zset:$queueKey"

    private fun admittedKey(queueKey: String): String = "queue:admitted:$queueKey"

    private fun joinedKey(queueKey: String, subject: String): String = "queue:joined:$queueKey:$subject"

    private fun ticketKey(ticketId: String): String = "queue:ticket:$ticketId"

    private fun createAdmitToken(queueKey: String, ticketId: String, expiresAt: Instant): String {
        val payloadRaw = "$queueKey|$ticketId|${expiresAt.epochSecond}|${UUID.randomUUID()}"
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadRaw.toByteArray(StandardCharsets.UTF_8))
        val signature = hmac(payload)
        return "$payload.$signature"
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private data class QueueTicket(
        val id: String,
        val queueKey: String,
        val subject: String,
        val issuedAt: Instant,
        val expiresAt: Instant,
    )
}

data class QueueJoinData(
    val queue_key: String,
    val ticket: String,
    val position: Int,
    val estimated_wait_seconds: Int,
)

data class QueueStatusData(
    val state: String,
    val position: Int,
    val estimated_wait_seconds: Int,
    val admit_token: String?,
)
