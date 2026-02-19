package com.devoceanblue.stayvista.domain.chat

import org.springframework.stereotype.Component

@Component
class ChatRoutingPolicy {
    fun extractSlots(request: ChatRecommendRequest): ChatSlots {
        val context = request.context
        val message = request.message.lowercase()

        val city = context.asString("city")
            ?: extractCity(message)

        val days = context.asInt("days")
            ?: extractDays(message)

        val budget = context.asLong("budget_krw")
            ?: extractBudget(message)

        val companions = context.asString("companions")
            ?: extractCompanions(message)

        val sourceTypes = context.asSourceTypes("source_types")

        return ChatSlots(
            city = city,
            days = days,
            budgetKrw = budget,
            companions = companions,
            intent = extractIntent(message),
            sourceTypes = sourceTypes,
        )
    }

    fun decide(
        message: String,
        slots: ChatSlots,
        ragHits: List<RagHit>,
        llmAllowed: Boolean = true,
    ): ChatRouteDecision {
        if (slots.city == null) {
            return ChatRouteDecision(
                type = ChatRouteType.ASK_CLARIFICATION,
                reason = "city_missing",
                followups = listOf(
                    "어느 도시 여행을 원하시나요? (예: 서울, 부산, 제주)",
                    "원하시는 출발 시점이나 여행 일수도 알려주시면 더 정확해요.",
                ),
            )
        }

        if (ragHits.isEmpty()) {
            return ChatRouteDecision(
                type = ChatRouteType.ASK_CLARIFICATION,
                reason = "insufficient_sources",
                followups = listOf(
                    "여행 도시를 조금 더 구체적으로 알려주실 수 있을까요?",
                    "예산 또는 선호(전시/맛집/자연)를 함께 알려주시면 추천 정확도가 올라갑니다.",
                ),
            )
        }

        if (ragHits.size < 2) {
            if (slots.intent == "GENERAL" || !canServeSingleHit(slots, ragHits.firstOrNull())) {
                return ChatRouteDecision(
                    type = ChatRouteType.ASK_CLARIFICATION,
                    reason = "insufficient_sources",
                    followups = listOf(
                        "여행 도시를 조금 더 구체적으로 알려주실 수 있을까요?",
                        "예산 또는 선호(전시/맛집/자연)를 함께 알려주시면 추천 정확도가 올라갑니다.",
                    ),
                )
            }
        }

        val normalized = message.lowercase()
        val llmKeywords = listOf("일정", "plan", "itinerary", "코스", "동선", "why", "설명", "비교")
        val shouldUseLlm = llmKeywords.any { normalized.contains(it) } || normalized.length >= 45

        return if (shouldUseLlm && llmAllowed) {
            ChatRouteDecision(
                type = ChatRouteType.LLM,
                reason = "natural_language_needed",
            )
        } else if (shouldUseLlm) {
            ChatRouteDecision(
                type = ChatRouteType.TEMPLATE,
                reason = "llm_disabled",
                followups = defaultFollowups(slots),
            )
        } else {
            ChatRouteDecision(
                type = ChatRouteType.TEMPLATE,
                reason = "rag_is_sufficient",
                followups = defaultFollowups(slots),
            )
        }
    }

    fun defaultFollowups(slots: ChatSlots): List<String> {
        val city = slots.city ?: "도시"
        return listOf(
            "$city 숙소는 도심형/휴양형 중 어떤 스타일이 좋으세요?",
            "실내 위주 일정으로 바꿀까요, 야외 중심으로 구성할까요?",
        )
    }

    fun needsItinerary(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("일정") ||
            normalized.contains("동선") ||
            normalized.contains("itinerary") ||
            normalized.contains("plan")
    }

    private fun extractCity(message: String): String? {
        return when {
            message.contains("seoul") || message.contains("서울") -> "Seoul"
            message.contains("busan") || message.contains("부산") -> "Busan"
            message.contains("jeju") || message.contains("제주") -> "Jeju"
            message.contains("incheon") || message.contains("인천") -> "Incheon"
            else -> null
        }
    }

    private fun extractDays(message: String): Int? {
        Regex("(\\d+)\\s*박").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { nights ->
            return (nights + 1).coerceAtLeast(1)
        }
        Regex("(\\d+)\\s*일").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { days ->
            return days.coerceAtLeast(1)
        }
        return null
    }

    private fun extractBudget(message: String): Long? {
        Regex("(\\d{1,3})(?:\\s*만)?\\s*원").find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { base ->
            return if (message.contains("만")) base * 10_000 else base
        }
        return null
    }

    private fun extractCompanions(message: String): String? {
        return when {
            message.contains("커플") || message.contains("연인") -> "COUPLE"
            message.contains("가족") -> "FAMILY"
            message.contains("혼자") || message.contains("solo") -> "SOLO"
            message.contains("친구") -> "FRIENDS"
            else -> null
        }
    }

    private fun extractIntent(message: String): String {
        return when {
            message.contains("맛집") || message.contains("food") -> "FOOD"
            message.contains("쇼핑") || message.contains("shopping") || message.contains("팝업") -> "SHOPPING"
            message.contains("전시") || message.contains("museum") -> "CULTURE"
            message.contains("관광") || message.contains("명소") || message.contains("attraction") -> "ATTRACTION"
            message.contains("휴양") || message.contains("resort") -> "RELAX"
            message.contains("액티비티") || message.contains("activity") -> "ACTIVITY"
            else -> "GENERAL"
        }
    }

    private fun canServeSingleHit(slots: ChatSlots, hit: RagHit?): Boolean {
        val candidate = hit ?: return false
        val sourceType = candidate.document.sourceType.uppercase()
        if (sourceType != "POI") {
            return false
        }

        val requestedCity = slots.city ?: return true
        val hitCity = candidate.document.metadata["city"]?.toString()?.trim()
        if (hitCity.isNullOrBlank()) {
            return true
        }
        return hitCity.equals(requestedCity, ignoreCase = true)
    }

    private fun Map<String, Any?>.asString(key: String): String? {
        return this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun Map<String, Any?>.asInt(key: String): Int? {
        val raw = this[key] ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            else -> raw.toString().toIntOrNull()
        }
    }

    private fun Map<String, Any?>.asLong(key: String): Long? {
        val raw = this[key] ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().toLongOrNull()
        }
    }

    private fun Map<String, Any?>.asSourceTypes(key: String): Set<String> {
        val raw = this[key] ?: return emptySet()

        val values = when (raw) {
            is Collection<*> -> raw.mapNotNull { it?.toString() }
            else -> raw.toString().split(',')
        }

        return values
            .map { it.trim().uppercase() }
            .filter { it in setOf("PROPERTY", "TICKET", "PACKAGE", "POI") }
            .toSet()
    }
}
