package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper

class ChatMemoryServiceTest {
    private val service = ChatMemoryService(
        redisTemplate = StringRedisTemplate(),
        objectMapper = jacksonObjectMapper(),
        meterRegistry = SimpleMeterRegistry(),
        ttlSeconds = 3_600,
    )

    @Test
    fun `appendTurn should redact pii and cap running summary after long chat`() {
        val sessionKey = "user:1001"
        repeat(50) { idx ->
            service.appendTurn(
                sessionKey = sessionKey,
                userMessage = "내 이메일 test$idx@example.com 이고 전화번호는 010-1234-5678, 카드 4242 4242 4242 4242 이야.",
                assistantMessage = "안내 메시지 $idx",
            )
        }

        val snapshot = service.load(sessionKey)
        assertEquals(50, snapshot.turnCount)
        assertTrue(snapshot.runningSummary.length <= 900)
        assertFalse(snapshot.runningSummary.contains("@example.com"))
        assertFalse(snapshot.runningSummary.contains("010-1234-5678"))
        assertFalse(snapshot.runningSummary.contains("4242 4242 4242 4242"))
        assertTrue(snapshot.runningSummary.contains("[REDACTED_EMAIL]"))
        assertTrue(snapshot.runningSummary.contains("[REDACTED_PHONE]"))
        assertTrue(snapshot.runningSummary.contains("[REDACTED_CARD]"))
    }

    @Test
    fun `derive state should move collecting to planning then booking ready`() {
        val sessionKey = "user:2002"
        service.appendTurn(sessionKey, "서울 일정 plan 짜줘", "네 일정 추천할게요.")
        assertEquals("PLANNING", service.load(sessionKey).state)

        service.appendTurn(sessionKey, "이제 예약 결제할래", "결제 단계 안내드릴게요.")
        assertEquals("BOOKING_READY", service.load(sessionKey).state)

        service.appendTurn(sessionKey, "다른 질문", "답변")
        assertEquals("BOOKING_READY", service.load(sessionKey).state)
    }
}
