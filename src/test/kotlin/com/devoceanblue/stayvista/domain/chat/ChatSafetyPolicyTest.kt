package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatSafetyPolicyTest {
    private val policy = ChatSafetyPolicy(SimpleMeterRegistry())

    @Test
    fun `evaluateInput blocks pii text`() {
        val decision = policy.evaluateInput("내 카드번호는 4111 1111 1111 1111 이야")
        assertTrue(decision.blocked)
    }

    @Test
    fun `evaluateInput allows normal travel request`() {
        val decision = policy.evaluateInput("서울 2박3일 여행 추천해줘")
        assertFalse(decision.blocked)
    }
}
