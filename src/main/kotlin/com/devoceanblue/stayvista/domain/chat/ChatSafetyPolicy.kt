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
    private val overclaimPatterns = listOf(
        Regex("확정", RegexOption.IGNORE_CASE),
        Regex("보장", RegexOption.IGNORE_CASE),
        Regex("100\\s*%", RegexOption.IGNORE_CASE),
    )
    private val outputBlockedMessage = "안전한 추천을 위해 시스템 규칙과 무관한 지시는 제외하고 다시 안내할게요."
    private val copilotGuardedMessage = "실시간 요금/재고/취소 정책은 확정 데이터 기준으로 다시 확인해 드릴게요."

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

    fun enforceCopilotOutputPolicy(result: ChatCopilotOrchestrateData): ChatCopilotOrchestrateData {
        var guardTriggered = false
        val toolAvailability = extractAvailabilityCount(result.evidence)
        val hasPolicySource = hasSourceType(result.evidence, "check_availability") || hasSourceType(result.evidence, "get_property_detail")
        val hasPriceSource = hasSourceType(result.evidence, "search_properties") || hasSourceType(result.evidence, "get_price_calendar")

        var safeAnswer = sanitizeText(result.answer)
        val injected = containsPromptInjection(result.answer)
        if (injected) {
            meterRegistry.counter("chat_guardrails_block_total", "reason", "prompt_injection_output").increment()
            safeAnswer = copilotGuardedMessage
            guardTriggered = true
        }

        if (toolAvailability != null && toolAvailability <= 0 && safeAnswer.contains("예약 가능")) {
            safeAnswer = safeAnswer.replace("예약 가능", "예약 가능 여부 확인 필요")
            meterRegistry.counter("chat_copilot_guardrail_violation_total", "reason", "inventory_overclaim").increment()
            guardTriggered = true
        }

        if (containsOverclaim(safeAnswer)) {
            safeAnswer = rewriteOverclaim(safeAnswer)
            meterRegistry.counter("chat_copilot_guardrail_violation_total", "reason", "overclaim_wording").increment()
            guardTriggered = true
        }

        if (safeAnswer.contains("무료 취소") && !hasPolicySource) {
            safeAnswer = safeAnswer.replace("무료 취소", "취소 정책 확인 필요")
            meterRegistry.counter("chat_copilot_guardrail_violation_total", "reason", "refund_without_source").increment()
            guardTriggered = true
        }

        if (containsPriceExpression(safeAnswer) && !hasPriceSource) {
            safeAnswer += " 요금 정보는 상세 화면에서 최신 값을 확인해 주세요."
            meterRegistry.counter("chat_copilot_guardrail_violation_total", "reason", "price_without_source").increment()
            guardTriggered = true
        }

        val safeEvidence = result.evidence
            .map { evidence -> sanitizeEvidence(evidence) }
            .ifEmpty {
                meterRegistry.counter("chat_copilot_guardrail_violation_total", "reason", "evidence_missing_all").increment()
                guardTriggered = true
                listOf(
                    ChatCopilotEvidence(
                        subject = "추천 근거",
                        why_recommended = listOf("현재 조건에 맞는 후보를 재확인하고 있습니다."),
                        cautions = listOf("요금/재고/취소 정책은 확정 단계에서 다시 확인됩니다."),
                        source_refs = listOf(
                            ChatCopilotSourceRef(
                                source_type = "system",
                                source_id = "guardrail:fallback",
                                title = "근거 보강",
                                value = "evidence_fallback",
                            ),
                        ),
                    ),
                )
            }

        val safeActions = result.actions.ifEmpty {
            guardTriggered = true
            meterRegistry.counter("chat_copilot_guardrail_violation_total", "reason", "actions_missing").increment()
            listOf(
                ChatCopilotAction(
                    type = "retry_with_patch",
                    label = "조건 조정 후 다시 추천",
                    payload = emptyMap(),
                ),
            )
        }

        val adjustedConfidence = when {
            guardTriggered -> (result.confidence - 0.12).coerceIn(0.1, 0.95)
            else -> result.confidence.coerceIn(0.1, 0.95)
        }

        if (guardTriggered) {
            meterRegistry.counter("chat_copilot_guardrail_rewrite_total").increment()
        }

        return result.copy(
            answer = safeAnswer,
            actions = safeActions,
            evidence = safeEvidence,
            confidence = adjustedConfidence,
            degraded = result.degraded || guardTriggered,
        )
    }

    private fun sanitizeEvidence(evidence: ChatCopilotEvidence): ChatCopilotEvidence {
        var guardTriggered = false
        val cleanWhy = evidence.why_recommended
            .map { sanitizeEvidenceText(it) }
            .filter { it.isNotBlank() }
            .take(3)
            .toMutableList()
        if (cleanWhy.isEmpty()) {
            cleanWhy += "추천 근거 데이터가 부족해 추가 확인이 필요합니다."
            meterRegistry.counter("chat_copilot_guardrail_violation_total", "reason", "why_missing").increment()
            guardTriggered = true
        }

        val cleanCautions = evidence.cautions
            .map { sanitizeEvidenceText(it) }
            .filter { it.isNotBlank() }
            .take(3)
            .toMutableList()
        if (cleanCautions.isEmpty()) {
            cleanCautions += "요금/재고/취소 정책은 확정 단계에서 다시 확인됩니다."
            meterRegistry.counter("chat_copilot_guardrail_violation_total", "reason", "caution_missing").increment()
            guardTriggered = true
        }

        val cleanRefs = evidence.source_refs
            .filterNot { containsPromptInjection("${it.title} ${it.value ?: ""}") }
            .map { ref ->
                ref.copy(
                    source_type = sanitizeText(ref.source_type),
                    source_id = sanitizeText(ref.source_id),
                    title = sanitizeText(ref.title),
                    value = ref.value?.let { sanitizeEvidenceText(it) },
                )
            }
            .toMutableList()
        if (cleanRefs.isEmpty()) {
            cleanRefs += ChatCopilotSourceRef(
                source_type = "system",
                source_id = "guardrail:auto-source",
                title = "근거 보강",
                value = "source_missing",
            )
            meterRegistry.counter("chat_copilot_guardrail_violation_total", "reason", "source_missing").increment()
            guardTriggered = true
        }

        if (guardTriggered) {
            meterRegistry.counter("chat_copilot_guardrail_rewrite_total").increment()
        }

        return evidence.copy(
            subject = sanitizeText(evidence.subject),
            why_recommended = cleanWhy,
            cautions = cleanCautions,
            source_refs = cleanRefs,
        )
    }

    private fun extractAvailabilityCount(evidence: List<ChatCopilotEvidence>): Int? {
        val availabilityRef = evidence
            .flatMap { it.source_refs }
            .firstOrNull { it.source_type.equals("check_availability", ignoreCase = true) }
            ?: return null
        val raw = availabilityRef.value ?: return null
        val match = Regex("available_room_types\\s*=\\s*(\\d+)").find(raw) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun hasSourceType(evidence: List<ChatCopilotEvidence>, sourceType: String): Boolean {
        return evidence.any { item ->
            item.source_refs.any { ref -> ref.source_type.equals(sourceType, ignoreCase = true) }
        }
    }

    private fun containsOverclaim(text: String): Boolean {
        return overclaimPatterns.any { pattern -> pattern.containsMatchIn(text) }
    }

    private fun rewriteOverclaim(text: String): String {
        var rewritten = text
        rewritten = rewritten.replace(Regex("확정", RegexOption.IGNORE_CASE), "안내")
        rewritten = rewritten.replace(Regex("보장", RegexOption.IGNORE_CASE), "가능성")
        rewritten = rewritten.replace(Regex("100\\s*%", RegexOption.IGNORE_CASE), "높은")
        return rewritten
    }

    private fun containsPriceExpression(text: String): Boolean {
        if (text.contains(Regex("[₩\\$¥€]"))) return true
        return text.contains(Regex("\\b\\d{1,3}(,\\d{3})+\\b"))
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
