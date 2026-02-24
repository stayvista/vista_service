package com.devoceanblue.stayvista.domain.chat

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Duration
import java.time.OffsetDateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class ChatWidgetSessionService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val piiRedactor: PiiRedactor,
    @Value("\${stayvista.chat.widget.snapshot.ttl-seconds:604800}") private val ttlSeconds: Long,
    @Value("\${stayvista.chat.widget.snapshot.schema-version:1}") private val schemaVersion: Int,
    @Value("\${stayvista.chat.widget.snapshot.max-bytes:65536}") private val maxBytes: Int,
) {
    fun save(userId: Long, request: ChatWidgetSessionSaveRequest): ChatWidgetSessionSaveData {
        if (request.schema_version <= 0) {
            recordSave("validation_error")
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "schema_version must be positive",
            )
        }

        if (request.schema_version != schemaVersion) {
            recordSave("validation_error")
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "Unsupported schema_version: ${request.schema_version}",
            )
        }

        val sanitizedState = sanitizeState(request.state)
        val payloadBytes = runCatching {
            objectMapper.writeValueAsBytes(sanitizedState).size
        }.getOrDefault(0)

        if (payloadBytes > maxBytes) {
            recordSave("validation_error")
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "snapshot payload too large",
            )
        }

        val now = OffsetDateTime.now().toString()
        val row = StoredWidgetSnapshot(
            schema_version = request.schema_version,
            state = sanitizedState,
            updated_at = now,
        )
        val key = snapshotKey(userId)

        val writeResult = runCatching {
            redisTemplate.opsForValue().set(
                key,
                objectMapper.writeValueAsString(row),
                Duration.ofSeconds(ttlSeconds.coerceAtLeast(60)),
            )
        }

        if (writeResult.isFailure) {
            recordSave("write_error")
            return ChatWidgetSessionSaveData(
                accepted = false,
                schema_version = request.schema_version,
                updated_at = now,
            )
        }

        meterRegistry.summary("ai_widget_snapshot_payload_bytes").record(payloadBytes.toDouble())
        recordSave("success")
        return ChatWidgetSessionSaveData(
            accepted = true,
            schema_version = request.schema_version,
            updated_at = now,
        )
    }

    fun load(userId: Long): ChatWidgetSessionLoadData {
        val key = snapshotKey(userId)
        val raw = runCatching { redisTemplate.opsForValue().get(key) }
            .getOrElse {
                recordLoad("read_error")
                return ChatWidgetSessionLoadData(
                    has_snapshot = false,
                )
            }

        if (raw.isNullOrBlank()) {
            recordLoad("miss")
            return ChatWidgetSessionLoadData(has_snapshot = false)
        }

        val parsed = runCatching {
            objectMapper.readValue(raw, StoredWidgetSnapshot::class.java)
        }.getOrElse {
            recordLoad("parse_error")
            return ChatWidgetSessionLoadData(has_snapshot = false)
        }

        if (parsed.schema_version != schemaVersion) {
            recordLoad("invalid_schema")
            return ChatWidgetSessionLoadData(has_snapshot = false)
        }

        recordLoad("hit")
        return ChatWidgetSessionLoadData(
            has_snapshot = true,
            schema_version = parsed.schema_version,
            updated_at = parsed.updated_at,
            state = parsed.state,
        )
    }

    private fun sanitizeState(state: Map<String, Any?>): Map<String, Any?> {
        return sanitizeAny(state) as? Map<String, Any?> ?: emptyMap()
    }

    private fun sanitizeAny(value: Any?): Any? {
        return when (value) {
            null -> null
            is String -> piiRedactor.redact(value).take(600)
            is Number, is Boolean -> value
            is Map<*, *> -> value.entries
                .mapNotNull { (k, v) ->
                    val key = k?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    key to sanitizeAny(v)
                }.toMap()
            is Iterable<*> -> value.map { sanitizeAny(it) }
            else -> piiRedactor.redact(value.toString()).take(600)
        }
    }

    private fun recordSave(result: String) {
        meterRegistry.counter("ai_widget_snapshot_save_total", Tags.of("result", result)).increment()
    }

    private fun recordLoad(result: String) {
        meterRegistry.counter("ai_widget_snapshot_load_total", Tags.of("result", result)).increment()
    }

    private fun snapshotKey(userId: Long): String {
        return "chat:widget:snapshot:user:$userId"
    }
}

data class ChatWidgetSessionSaveRequest(
    val schema_version: Int,
    val state: Map<String, Any?> = emptyMap(),
)

data class ChatWidgetSessionSaveData(
    val accepted: Boolean,
    val schema_version: Int,
    val updated_at: String,
)

data class ChatWidgetSessionLoadData(
    val has_snapshot: Boolean,
    val schema_version: Int? = null,
    val updated_at: String? = null,
    val state: Map<String, Any?>? = null,
)

private data class StoredWidgetSnapshot(
    val schema_version: Int,
    val state: Map<String, Any?>,
    val updated_at: String,
)
