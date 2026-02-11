package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class ChatSafetyPolicy(
    private val meterRegistry: MeterRegistry,
    private val piiRedactor: PiiRedactor,
) {
    private val blockedKeywords = listOf("폭탄", "마약", "해킹", "총기", "살인", "불법")
    private val promptInjectionPatterns = listOf(
        Regex("ignore\\s+(all\\s+)?previous\\s+instructions?", RegexOption.IGNORE_CASE),
        Regex("system\\s+prompt", RegexOption.IGNORE_CASE),
        Regex("developer\\s+message", RegexOption.IGNORE_CASE),
        Regex("reveal\\s+your\\s+instructions?", RegexOption.IGNORE_CASE),
        Regex("act\\s+as\\s+.*(jailbreak|dan)", RegexOption.IGNORE_CASE),
        Regex("규칙\\s*무시", RegexOption.IGNORE_CASE),
        Regex("지시\\s*무시", RegexOption.IGNORE_CASE),
        Regex("프롬프트\\s*노출", RegexOption.IGNORE_CASE),
        Regex("시스템\\s*메시지", RegexOption.IGNORE_CASE),
        Regex("```\\s*(system|assistant|developer)", RegexOption.IGNORE_CASE),
    )
    private val outputBlockedMessage = "안전한 추천을 위해 시스템 규칙과 무관한 지시는 제외하고 다시 안내할게요."

    fun evaluateInput(message: String): SafetyDecision {
        val normalized = message.lowercase()
        if (containsSensitiveInfo(normalized)) {
            meterRegistry.counter("chat_guardrails_block_total", "reason", "pii").increment()
            return SafetyDecision(
                blocked = true,
                reason = "개인정보/결제정보가 포함된 요청은 처리할 수 없습니다. 민감한 정보는 제거하고 다시 요청해 주세요.",
            )
        }

        if (containsPromptInjection(message)) {
            meterRegistry.counter("chat_guardrails_block_total", "reason", "prompt_injection_input").increment()
            return SafetyDecision(
                blocked = true,
                reason = "시스템 규칙 변경/노출 요청은 허용되지 않습니다. 여행 조건 위주로 다시 요청해 주세요.",
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
        val normalized = message.lowercase()
        return !containsSensitiveInfo(normalized) && !containsPromptInjection(message)
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
        promptInjectionPatterns.forEach { pattern ->
            sanitized = sanitized.replace(pattern, "[FILTERED_INJECTION]")
        }
        return sanitized.trim()
    }

    fun sanitizeEvidenceText(text: String): String {
        val flattened = text.replace(Regex("[\\r\\n]+"), " ").trim()
        if (flattened.isBlank()) return ""
        if (containsPromptInjection(flattened)) {
            meterRegistry.counter("chat_guardrails_block_total", "reason", "prompt_injection_doc").increment()
            return "[FILTERED_EVIDENCE]"
        }
        return sanitizeText(flattened).take(240)
    }

    fun enforceOutputPolicy(result: ChatRecommendData): ChatRecommendData {
        val outputInjection = containsPromptInjection(result.assistant_text) || containsPromptInjection(result.answer)
        val safeCards = result.cards
            .filterNot { containsPromptInjection("${it.title} ${it.why ?: ""}") }
            .map { card ->
                card.copy(
                    title = sanitizeText(card.title),
                    why = card.why?.let { sanitizeText(it) },
                )
            }
        val safeSources = result.sources.filterNot { source ->
            containsPromptInjection("${source.title} ${source.snippet}")
        }
        val safeFollowups = result.followups
            .map { sanitizeText(it) }
            .filter { it.isNotBlank() }
            .take(2)

        if (outputInjection) {
            meterRegistry.counter("chat_guardrails_block_total", "reason", "prompt_injection_output").increment()
            return result.copy(
                answer = outputBlockedMessage,
                assistant_text = outputBlockedMessage,
                cards = safeCards,
                sources = safeSources,
                followups = if (safeFollowups.isNotEmpty()) safeFollowups else listOf("여행 조건(도시/일정/예산)만 알려주세요."),
                llm_used = false,
                context_used = result.context_used + mapOf("prompt_injection_guard" to "output_blocked"),
            )
        }

        return result.copy(
            answer = sanitizeText(result.answer),
            assistant_text = sanitizeText(result.assistant_text),
            cards = safeCards,
            sources = safeSources,
            followups = safeFollowups.ifEmpty { result.followups.take(2) },
        )
    }

    private fun containsSensitiveInfo(text: String): Boolean {
        return piiRedactor.containsPii(text)
    }

    private fun containsPromptInjection(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return false
        }
        return promptInjectionPatterns.any { pattern -> pattern.containsMatchIn(normalized) }
    }
}

data class SafetyDecision(
    val blocked: Boolean,
    val reason: String?,
)
