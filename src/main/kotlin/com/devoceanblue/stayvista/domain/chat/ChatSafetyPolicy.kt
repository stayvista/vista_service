package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class ChatSafetyPolicy(
    private val meterRegistry: MeterRegistry,
) {
    private val blockedKeywords = listOf("폭탄", "마약", "해킹", "총기", "살인", "불법")

    fun evaluateInput(message: String): SafetyDecision {
        val normalized = message.lowercase()
        if (containsSensitiveInfo(normalized)) {
            meterRegistry.counter("chat_guardrails_block_total", "reason", "pii").increment()
            return SafetyDecision(
                blocked = true,
                reason = "개인정보/결제정보가 포함된 요청은 처리할 수 없습니다. 민감한 정보는 제거하고 다시 요청해 주세요.",
            )
        }

        if (blockedKeywords.any { keyword -> normalized.contains(keyword) }) {
            meterRegistry.counter("chat_guardrails_block_total", "reason", "policy").increment()
            return SafetyDecision(
                blocked = true,
                reason = "정책상 지원하지 않는 요청입니다. 여행/숙소/티켓 추천과 관련된 질문으로 요청해 주세요.",
            )
        }

        return SafetyDecision(blocked = false, reason = null)
    }

    fun cacheAllowed(message: String): Boolean {
        return !containsSensitiveInfo(message.lowercase())
    }

    fun filterCardsWithSource(cards: List<ChatCard>): List<ChatCard> {
        val filtered = cards.filter { card -> card.source.isNotEmpty() }
        if (cards.isNotEmpty() && filtered.isEmpty()) {
            meterRegistry.counter("chat_guardrails_block_total", "reason", "source_missing").increment()
        }
        return filtered
    }

    fun sanitizeText(text: String): String {
        var sanitized = text
        blockedKeywords.forEach { keyword ->
            if (sanitized.contains(keyword, ignoreCase = true)) {
                sanitized = sanitized.replace(keyword, "[제한됨]", ignoreCase = true)
            }
        }
        return sanitized.trim()
    }

    private fun containsSensitiveInfo(text: String): Boolean {
        val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val phoneRegex = Regex("(01[016789])[ -]?(\\d{3,4})[ -]?(\\d{4})")
        val cardRegex = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
        return emailRegex.containsMatchIn(text) ||
            phoneRegex.containsMatchIn(text) ||
            cardRegex.containsMatchIn(text)
    }
}

data class SafetyDecision(
    val blocked: Boolean,
    val reason: String?,
)
