package com.devoceanblue.stayvista.domain.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PiiRedactorTest {
    private val redactor = PiiRedactor()

    @Test
    fun `containsPii should detect sensitive tokens`() {
        assertTrue(redactor.containsPii("연락처는 010-1234-5678 입니다."))
        assertTrue(redactor.containsPii("카드는 4111 1111 1111 1111"))
        assertTrue(redactor.containsPii("메일 test@example.com"))
        assertFalse(redactor.containsPii("서울 2박3일 여행 추천"))
    }

    @Test
    fun `redact should mask all pii patterns`() {
        val redacted = redactor.redact("01012345678, test@example.com, 4111 1111 1111 1111")
        assertTrue(redacted.contains("[REDACTED_PHONE]"))
        assertTrue(redacted.contains("[REDACTED_EMAIL]"))
        assertTrue(redacted.contains("[REDACTED_CARD]"))
    }
}
