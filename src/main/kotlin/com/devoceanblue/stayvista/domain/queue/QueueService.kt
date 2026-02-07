package com.devoceanblue.stayvista.domain.queue

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class QueueService(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${stayvista.queue.max-admitted-per-key:100}") private val maxAdmittedPerKey: Int,
    @Value("\${stayvista.queue.ticket-ttl-seconds:1800}") private val ticketTtlSeconds: Long,
    @Value("\${stayvista.queue.admit-token-ttl-seconds:30}") private val admitTokenTtlSeconds: Long,
    @Value("\${stayvista.queue.secret:stayvista-queue-secret}") private val secret: String,
) {
    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val tickets = ConcurrentHashMap<String, QueueTicket>()
    private val joinedByKey = ConcurrentHashMap<String, String>()
    private val admitted = ConcurrentHashMap<String, MutableMap<String, Instant>>()

    fun join(queueKey: String, subject: String): QueueJoinData {
        val now = Instant.now(clock)
        val dedupeKey = "$queueKey:$subject"
        val existingTicketId = joinedByKey[dedupeKey]
        if (existingTicketId != null) {
            val existing = tickets[existingTicketId]
            if (existing != null && existing.expiresAt.isAfter(now)) {
                val position = queuePosition(queueKey, existingTicketId)
                return QueueJoinData(
                    queue_key = queueKey,
                    ticket = existingTicketId,
                    position = if (position < 0) 0 else position,
                    estimated_wait_seconds = estimateWaitSeconds(position),
                )
            }
        }

        val ticketId = "qtk_${UUID.randomUUID()}"
        val ticket = QueueTicket(
            id = ticketId,
            queueKey = queueKey,
            subject = subject,
            issuedAt = now,
            expiresAt = now.plusSeconds(ticketTtlSeconds),
        )
        tickets[ticketId] = ticket
        joinedByKey[dedupeKey] = ticketId
        val queue = queues.computeIfAbsent(queueKey) { ConcurrentLinkedQueue() }
        queue.add(ticketId)
        meterRegistry.counter("queue_join_total").increment()

        val position = queuePosition(queueKey, ticketId)
        return QueueJoinData(
            queue_key = queueKey,
            ticket = ticketId,
            position = position,
            estimated_wait_seconds = estimateWaitSeconds(position),
        )
    }

    fun status(ticketId: String): QueueStatusData {
        val now = Instant.now(clock)
        val ticket = tickets[ticketId] ?: return QueueStatusData(
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
        val admittedForKey = admitted.computeIfAbsent(ticket.queueKey) { ConcurrentHashMap() }
        val admittedUntil = admittedForKey[ticket.id]
        if (admittedUntil != null && admittedUntil.isAfter(now)) {
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

        val admittedForKey = admitted[queueKey] ?: return false
        val admittedUntil = admittedForKey[ticketId] ?: return false
        return admittedUntil.isAfter(Instant.now(clock))
    }

    fun requireValidAdmitToken(token: String?) {
        if (token.isNullOrBlank() || !validateAdmitToken(token)) {
            throw DomainException(ErrorCode.QUEUE_REQUIRED, "Queue admit token is required")
        }
    }

    private fun admit(queueKey: String) {
        val now = Instant.now(clock)
        val queue = queues.computeIfAbsent(queueKey) { ConcurrentLinkedQueue() }
        val admittedForKey = admitted.computeIfAbsent(queueKey) { ConcurrentHashMap() }
        admittedForKey.entries.removeIf { (_, expiry) -> expiry.isBefore(now) }

        while (admittedForKey.size < maxAdmittedPerKey) {
            val nextTicketId = queue.poll() ?: break
            val ticket = tickets[nextTicketId] ?: continue
            if (ticket.expiresAt.isBefore(now)) {
                continue
            }
            admittedForKey[nextTicketId] = now.plusSeconds(admitTokenTtlSeconds)
            meterRegistry.counter("queue_admitted_total").increment()
        }
    }

    private fun queuePosition(queueKey: String, ticketId: String): Int {
        val list = queues[queueKey]?.toList() ?: return -1
        return list.indexOf(ticketId)
    }

    private fun estimateWaitSeconds(position: Int): Int {
        if (position <= 0) return 0
        return position / 5
    }

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

private data class QueueTicket(
    val id: String,
    val queueKey: String,
    val subject: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
)
