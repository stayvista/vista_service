package com.devoceanblue.stayvista.domain.chat

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import org.springframework.stereotype.Component

@Component
class ChatRoutingPolicy {
    fun extractSlots(request: ChatRecommendRequest): ChatSlots {
        val context = request.context
        val message = request.message.lowercase()

        val city = extractCity(message)
            ?: context.asString("city")

        val days = extractDays(message)
            ?: context.asInt("days")
            ?: context.daysFromDateRange()

        val budget = extractBudget(message)
            ?: context.asLong("budget_krw")

        val companions = extractCompanions(message)
            ?: context.asString("companions")
            ?: context.companionsFromGuests()

        val sourceTypes = extractSourceTypes(message)
            .ifEmpty { context.asSourceTypes("source_types") }

        return ChatSlots(
            city = city,
            days = days,
            budgetKrw = budget,
            companions = companions,
            intent = extractIntent(message),
            sourceTypes = sourceTypes,
        )
    }

    private fun extractSourceTypes(message: String): Set<String> {
        val normalized = message.lowercase()
        val detected = linkedSetOf<String>()

        if (containsAny(normalized, listOf("숙소", "호텔", "리조트", "펜션", "모텔", "property", "stay", "accommodation"))) {
            detected += "PROPERTY"
        }
        if (containsAny(normalized, listOf("패키지", "package", "bundle", "항공+숙소", "항공 + 숙소"))) {
            detected += "PACKAGE"
        }
        if (containsAny(normalized, listOf("티켓", "입장권", "전시권", "공연", "ticket", "pass"))) {
            detected += "TICKET"
        }
        if (containsAny(normalized, listOf("맛집", "관광", "명소", "주변", "식당", "카페", "food", "poi", "attraction", "shopping"))) {
            detected += "POI"
        }

        return detected
    }

    private fun containsAny(message: String, keywords: List<String>): Boolean {
        return keywords.any { message.contains(it.lowercase()) }
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
        var bestCity: String? = null
        var bestIndex = -1
        CITY_ALIASES.forEach { (city, aliases) ->
            aliases.forEach { alias ->
                val index = findCityToken(message, alias)
                if (index > bestIndex) {
                    bestCity = city
                    bestIndex = index
                }
            }
        }
        return bestCity
    }

    private fun findCityToken(message: String, token: String): Int {
        val normalized = message.lowercase()
        val keyword = token.lowercase()
        if (keyword.any { it.code > 127 }) {
            return normalized.lastIndexOf(keyword)
        }
        val regex = Regex("\\b${Regex.escape(keyword)}\\b")
        return regex.findAll(normalized).lastOrNull()?.range?.first ?: -1
    }

    private fun extractDays(message: String): Int? {
        Regex("(\\d+)\\s*박\\s*(\\d+)\\s*일").find(message)?.let { match ->
            val nights = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@let
            val days = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@let
            if (days >= nights) {
                return days.coerceAtLeast(1)
            }
            return (nights + 1).coerceAtLeast(1)
        }
        Regex("(\\d+)\\s*nights?\\s*(\\d+)\\s*days?", RegexOption.IGNORE_CASE).find(message)?.let { match ->
            val nights = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@let
            val days = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@let
            return maxOf(days, nights + 1).coerceAtLeast(1)
        }
        Regex("(\\d+)\\s*박").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { nights ->
            return (nights + 1).coerceAtLeast(1)
        }
        Regex("(\\d+)\\s*nights?", RegexOption.IGNORE_CASE).find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { nights ->
            return (nights + 1).coerceAtLeast(1)
        }
        Regex("(\\d+)\\s*일").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { days ->
            return days.coerceAtLeast(1)
        }
        Regex("(\\d+)\\s*days?", RegexOption.IGNORE_CASE).find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { days ->
            return days.coerceAtLeast(1)
        }
        return null
    }

    private fun extractBudget(message: String): Long? {
        val normalized = message.lowercase().replace(",", "")
        Regex("(\\d{1,4})\\s*(?:~|-|부터|to)\\s*(\\d{1,4})\\s*만\\s*원?").find(normalized)?.let { match ->
            val lower = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@let
            val upper = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return@let
            return maxOf(lower, upper) * 10_000
        }
        Regex("(\\d{1,4})\\s*만\\s*원대?").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { base ->
            return base * 10_000
        }
        Regex("(\\d{1,7})\\s*(usd|달러)").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { usd ->
            return (usd * USD_TO_KRW).roundToLong()
        }
        Regex("(\\d{1,7})\\s*(jpy|엔|엔화)").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { jpy ->
            return (jpy * JPY_TO_KRW).roundToLong()
        }
        Regex("(\\d{1,8})(?:\\s*만)?\\s*원").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { base ->
            return if (normalized.contains("만")) base * 10_000 else base
        }
        return null
    }

    private fun extractCompanions(message: String): String? {
        val normalized = message.lowercase()
        val adults = Regex("(?:성인|어른|adults?)\\s*(\\d{1,2})", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val children = Regex("(?:아동|어린이|아이|children?)\\s*(\\d{1,2})", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (adults != null || children != null) {
            val normalizedAdults = adults ?: 1
            val normalizedChildren = children ?: 0
            return when {
                normalizedChildren > 0 -> "FAMILY"
                normalizedAdults <= 1 -> "SOLO"
                normalizedAdults == 2 -> "COUPLE"
                else -> "FRIENDS"
            }
        }

        Regex("(\\d{1,2})\\s*명").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { headCount ->
            return when {
                normalized.contains("가족") || normalized.contains("아이") || normalized.contains("어린이") -> "FAMILY"
                headCount <= 1 -> "SOLO"
                headCount == 2 -> "COUPLE"
                else -> "FRIENDS"
            }
        }

        if (normalized.contains("출장") || normalized.contains("비즈니스") || normalized.contains("business")) {
            return "SOLO"
        }
        return when {
            normalized.contains("커플") || normalized.contains("연인") -> "COUPLE"
            normalized.contains("가족") -> "FAMILY"
            normalized.contains("혼자") || normalized.contains("solo") -> "SOLO"
            normalized.contains("친구") -> "FRIENDS"
            else -> null
        }
    }

    private fun extractIntent(message: String): String {
        val normalized = message.lowercase()
        return when {
            normalized.contains("출장") || normalized.contains("비즈니스") || normalized.contains("business") -> "BUSINESS"
            normalized.contains("맛집") || normalized.contains("food") || normalized.contains("미식") || normalized.contains("레스토랑") -> "FOOD"
            normalized.contains("쇼핑") || normalized.contains("shopping") || normalized.contains("팝업") -> "SHOPPING"
            normalized.contains("전시") || normalized.contains("museum") || normalized.contains("박물관") || normalized.contains("미술관") -> "CULTURE"
            normalized.contains("관광") || normalized.contains("명소") || normalized.contains("attraction") || normalized.contains("랜드마크") -> "ATTRACTION"
            normalized.contains("휴양") || normalized.contains("resort") || normalized.contains("힐링") -> "RELAX"
            normalized.contains("액티비티") || normalized.contains("activity") || normalized.contains("운동") -> "ACTIVITY"
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

    private fun Map<String, Any?>.daysFromDateRange(): Int? {
        val checkInRaw = this["check_in"]?.toString()?.trim().orEmpty()
        val checkOutRaw = this["check_out"]?.toString()?.trim().orEmpty()
        if (checkInRaw.isBlank() || checkOutRaw.isBlank()) {
            return null
        }
        val checkIn = parseDate(checkInRaw) ?: return null
        val checkOut = parseDate(checkOutRaw) ?: return null
        val nights = ChronoUnit.DAYS.between(checkIn, checkOut).toInt()
        if (nights <= 0) {
            return null
        }
        return (nights + 1).coerceAtLeast(1)
    }

    private fun Map<String, Any?>.companionsFromGuests(): String? {
        val guests = this["guests"] as? Map<*, *> ?: return null
        val adults = guests.number("adults") ?: return null
        val children = guests.number("children") ?: 0
        return when {
            children > 0 -> "FAMILY"
            adults <= 1 -> "SOLO"
            adults == 2 -> "COUPLE"
            else -> "FRIENDS"
        }
    }

    private fun Map<*, *>.number(key: String): Int? {
        val raw = this[key] ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            else -> raw.toString().toIntOrNull()
        }
    }

    private fun parseDate(value: String): LocalDate? {
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    companion object {
        private const val USD_TO_KRW = 1320.0
        private const val JPY_TO_KRW = 8.8

        private val CITY_ALIASES: Map<String, List<String>> = mapOf(
            "Seoul" to listOf("seoul", "서울"),
            "Busan" to listOf("busan", "부산"),
            "Jeju" to listOf("jeju", "제주"),
            "Incheon" to listOf("incheon", "인천"),
            "Gangneung" to listOf("gangneung", "강릉"),
            "Sokcho" to listOf("sokcho", "속초"),
            "Daegu" to listOf("daegu", "대구"),
            "Daejeon" to listOf("daejeon", "대전"),
            "Gyeongju" to listOf("gyeongju", "경주"),
            "Tokyo" to listOf("tokyo", "도쿄", "동경"),
            "Osaka" to listOf("osaka", "오사카"),
            "Fukuoka" to listOf("fukuoka", "후쿠오카"),
            "Bangkok" to listOf("bangkok", "방콕"),
            "Danang" to listOf("danang", "다낭"),
            "NhaTrang" to listOf("nhatrang", "나트랑"),
            "HoChiMinh" to listOf("hochiminh", "ho chi minh", "호치민"),
            "Taipei" to listOf("taipei", "타이베이", "타이페이"),
            "Singapore" to listOf("singapore", "싱가포르"),
            "Paris" to listOf("paris", "파리"),
            "London" to listOf("london", "런던"),
            "NewYork" to listOf("new york", "newyork", "뉴욕"),
        )
    }
}
