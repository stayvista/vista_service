package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    properties = [
        "stayvista.chat.widget.snapshot.schema-version=1",
        "stayvista.chat.widget.snapshot.max-bytes=65536",
    ],
)
class ChatWidgetSessionServiceTest {
    @Autowired
    lateinit var service: ChatWidgetSessionService

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockitoBean
    lateinit var redisTemplate: StringRedisTemplate

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `save should redact pii and store snapshot`() {
        val valueOps = org.mockito.Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        given(redisTemplate.opsForValue()).willReturn(valueOps)

        val result = service.save(
            userId = 1001L,
            request = ChatWidgetSessionSaveRequest(
                schema_version = 1,
                state = mapOf(
                    "messageDraft" to "연락처는 test@example.com, 010-1234-5678",
                    "messages" to listOf(
                        mapOf("role" to "user", "text" to "카드 4242 4242 4242 4242"),
                    ),
                ),
            ),
        )

        assertTrue(result.accepted)
        assertEquals(1, result.schema_version)

        val jsonCaptor = ArgumentCaptor.forClass(String::class.java)
        then(valueOps).should().set(
            eq("chat:widget:snapshot:user:1001"),
            jsonCaptor.capture(),
            any(Duration::class.java),
        )

        val stored = jsonCaptor.value
        assertTrue(stored.contains("[REDACTED_EMAIL]"))
        assertTrue(stored.contains("[REDACTED_PHONE]"))
        assertTrue(stored.contains("[REDACTED_CARD]"))
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_snapshot_save_total")
                .tag("result", "success")
                .counter()
                .count(),
            0.0001,
        )
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `load should return miss when no snapshot`() {
        val valueOps = org.mockito.Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        given(redisTemplate.opsForValue()).willReturn(valueOps)
        given(valueOps.get("chat:widget:snapshot:user:1001")).willReturn(null)

        val loaded = service.load(1001L)

        assertFalse(loaded.has_snapshot)
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_snapshot_load_total")
                .tag("result", "miss")
                .counter()
                .count(),
            0.0001,
        )
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `load should discard unsupported schema snapshot safely`() {
        val valueOps = org.mockito.Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        given(redisTemplate.opsForValue()).willReturn(valueOps)
        given(valueOps.get("chat:widget:snapshot:user:1001")).willReturn(
            objectMapper.writeValueAsString(
                mapOf(
                    "schema_version" to 99,
                    "updated_at" to "2026-02-24T10:00:00+09:00",
                    "state" to mapOf("messageDraft" to "hello"),
                ),
            ),
        )

        val loaded = service.load(1001L)

        assertFalse(loaded.has_snapshot)
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_snapshot_load_total")
                .tag("result", "invalid_schema")
                .counter()
                .count(),
            0.0001,
        )
    }
}
