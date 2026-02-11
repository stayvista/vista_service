package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class ChatMemoryService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val piiRedactor: PiiRedactor,
    @Value("\${stayvista.chat.memory.ttl-seconds:604800}") private val ttlSeconds: Long,
) {
    private val fallbackStore = ConcurrentHashMap<String, StoredMemoryState>()

    fun resolveSessionKey(request: ChatRecommendRequest): String {
        val context = request.context
        val userId = context["user_id"]?.toString()?.trim().orEmpty()
        if (userId.isNotBlank()) return "user:$userId"

        val sessionId = context["session_id"]?.toString()?.trim().orEmpty()
        if (sessionId.isNotBlank()) return "session:$sessionId"

        val conversationId = context["conversation_id"]?.toString()?.trim().orEmpty()
        if (conversationId.isNotBlank()) return "conversation:$conversationId"

        return "anon"
    }

    fun load(sessionKey: String): ChatMemorySnapshot {
        val key = memoryKey(sessionKey)
        val raw = runCatching { redisTemplate.opsForValue().get(key) }
            .getOrElse {
                meterRegistry.counter("chat_memory_total", "result", "read_error").increment()
                null
            }

        if (raw.isNullOrBlank()) {
            val fallback = fallbackStore[key]
            if (fallback != null) {
                meterRegistry.counter("chat_memory_total", "result", "fallback_hit").increment()
                return fallback.toSnapshot()
            }
            meterRegistry.counter("chat_memory_total", "result", "miss").increment()
            return ChatMemorySnapshot()
        }

        return runCatching {
            val state = objectMapper.readValue(raw, StoredMemoryState::class.java)
            meterRegistry.counter("chat_memory_total", "result", "hit").increment()
            ChatMemorySnapshot(
                state = state.state,
                runningSummary = state.runningSummary,
                turnCount = state.turnCount,
            )
        }.getOrElse {
            meterRegistry.counter("chat_memory_total", "result", "parse_error").increment()
            ChatMemorySnapshot()
        }
    }

    fun appendTurn(
        sessionKey: String,
        userMessage: String,
        assistantMessage: String,
    ) {
        if (sessionKey == "anon") {
            return
        }

        val current = load(sessionKey)
        val nextState = deriveState(current.state, userMessage)
        val updatedSummary = buildRunningSummary(current.runningSummary, userMessage, assistantMessage)

        val toStore = StoredMemoryState(
            state = nextState,
            runningSummary = updatedSummary,
            turnCount = current.turnCount + 1,
            updatedAt = OffsetDateTime.now().toString(),
        )
        val key = memoryKey(sessionKey)
        fallbackStore[key] = toStore

        runCatching {
            redisTemplate.opsForValue().set(
                key,
                objectMapper.writeValueAsString(toStore),
                Duration.ofSeconds(ttlSeconds.coerceAtLeast(60)),
            )
            meterRegistry.counter("chat_memory_total", "result", "write_success").increment()
        }.onFailure {
            meterRegistry.counter("chat_memory_total", "result", "write_error").increment()
        }
    }

    private fun memoryKey(sessionKey: String): String {
        return "chat:memory:$sessionKey"
    }

    private fun deriveState(currentState: String, userMessage: String): String {
        val normalized = userMessage.lowercase()
        if (normalized.contains("예약") || normalized.contains("결제") || normalized.contains("book") || normalized.contains("checkout")) {
            return "BOOKING_READY"
        }
        if (normalized.contains("일정") || normalized.contains("동선") || normalized.contains("itinerary") || normalized.contains("plan")) {
            return "PLANNING"
        }
        return if (currentState == "BOOKING_READY") "BOOKING_READY" else "COLLECTING"
    }

    private fun buildRunningSummary(existing: String, userMessage: String, assistantMessage: String): String {
        val cleanedUser = redactPii(userMessage).replace(Regex("\\s+"), " ").trim().take(180)
        val cleanedAssistant = redactPii(assistantMessage).replace(Regex("\\s+"), " ").trim().take(200)

        val appended = buildString {
            if (existing.isNotBlank()) {
                append(existing)
                append(" ")
            }
            append("U:")
            append(cleanedUser)
            append(" A:")
            append(cleanedAssistant)
        }

        return appended.takeLast(900)
    }

    private fun redactPii(text: String): String {
        return piiRedactor.redact(text)
    }

    private data class StoredMemoryState(
        val state: String = "COLLECTING",
        val runningSummary: String = "",
        val turnCount: Int = 0,
        val updatedAt: String = OffsetDateTime.now().toString(),
    )

    private fun StoredMemoryState.toSnapshot(): ChatMemorySnapshot {
        return ChatMemorySnapshot(
            state = state,
            runningSummary = runningSummary,
            turnCount = turnCount,
        )
    }
}

data class ChatMemorySnapshot(
    val state: String = "COLLECTING",
    val runningSummary: String = "",
    val turnCount: Int = 0,
)
