package com.devoceanblue.stayvista.domain.chat

import org.springframework.stereotype.Component

@Component
class PiiRedactor {
    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phoneRegex = Regex("(01[016789])[ -]?(\\d{3,4})[ -]?(\\d{4})")
    private val cardRegex = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
    private val rrnRegex = Regex("\\b\\d{6}[ -]?[1-4]\\d{6}\\b")

    fun containsPii(text: String): Boolean {
        return emailRegex.containsMatchIn(text) ||
            phoneRegex.containsMatchIn(text) ||
            cardRegex.containsMatchIn(text) ||
            rrnRegex.containsMatchIn(text)
    }

    fun redact(text: String): String {
        var sanitized = text
        sanitized = sanitized.replace(emailRegex, "[REDACTED_EMAIL]")
        sanitized = sanitized.replace(phoneRegex, "[REDACTED_PHONE]")
        sanitized = sanitized.replace(cardRegex, "[REDACTED_CARD]")
        sanitized = sanitized.replace(rrnRegex, "[REDACTED_RRN]")
        return sanitized
    }
}
