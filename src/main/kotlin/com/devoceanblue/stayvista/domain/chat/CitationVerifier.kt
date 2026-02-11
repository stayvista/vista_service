package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class CitationVerifier(
    private val meterRegistry: MeterRegistry,
) {
    private val assertivePatterns = listOf(
        Regex("\\b(확정|보장|무조건|항상|즉시)\\b"),
        Regex("(재고|잔여|가격|요금|환불|정책)"),
        Regex("\\d+[\\,\\d]*\\s*(원|KRW|만원)"),
    )

    fun verifyOrMitigate(result: ChatRecommendData): ChatRecommendData {
        val cardsWithSources = result.cards.filter { card -> card.source.isNotEmpty() || card.sources.isNotEmpty() }
        val normalizedSources = result.sources.ifEmpty { cardsWithSources.flatMap { it.source.ifEmpty { it.sources } } }
        val hasAssertion = hasAssertiveClaim(result.assistant_text)

        if (!hasAssertion) {
            return if (cardsWithSources.size == result.cards.size && normalizedSources.isNotEmpty()) {
                result
            } else {
                result.copy(
                    cards = cardsWithSources,
                    sources = normalizedSources,
                )
            }
        }

        if (normalizedSources.isNotEmpty()) {
            return result.copy(
                cards = cardsWithSources,
                sources = normalizedSources,
            )
        }

        meterRegistry.counter("citation_verifier_block_total", "reason", "assertion_without_source").increment()
        return result.copy(
            answer = "근거가 충분한 데이터만 기준으로 다시 추천할게요. 조건을 조금 더 구체적으로 알려주세요.",
            assistant_text = "근거가 충분한 데이터만 기준으로 다시 추천할게요. 조건을 조금 더 구체적으로 알려주세요.",
            cards = emptyList(),
            sources = emptyList(),
            llm_used = false,
            context_used = result.context_used + mapOf("citation_guard" to "assertion_blocked"),
        )
    }

    private fun hasAssertiveClaim(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return false
        }
        return assertivePatterns.any { it.containsMatchIn(normalized) }
    }
}
