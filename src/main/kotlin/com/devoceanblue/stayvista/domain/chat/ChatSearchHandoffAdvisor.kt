package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

@Service
class ChatSearchHandoffAdvisor(
    private val meterRegistry: MeterRegistry,
) {
    private val validSourceTypes = setOf("PROPERTY", "PACKAGE", "TICKET", "POI")

    fun recommend(
        message: String,
        slots: ChatSlots,
        retrievalHits: List<RagHit> = emptyList(),
        profile: PreferenceProfileSnapshot = PreferenceProfileSnapshot(),
    ): ChatSearchHandoffRecommendation {
        val normalized = message.lowercase()
        val filters = mutableListOf<ChatSearchHandoffFilter>()
        var signalScore = 0
        var profileApplied = false

        fun addFilter(
            key: String,
            value: String,
            label: String,
            reason: String,
            source: String = "rule",
            score: Int = 1,
        ): Boolean {
            val exists = filters.any { it.key == key && it.value == value }
            if (!exists) {
                filters += ChatSearchHandoffFilter(
                    key = key,
                    value = value,
                    label = label,
                    reason = reason,
                    source = source,
                )
                signalScore += score
                return true
            }
            return false
        }

        when (slots.intent) {
            "FOOD" -> addFilter("amenities", "restaurant", "레스토랑", "맛집/미식 위주 요청이라 식음 시설 필터를 우선 제안해요.")
            "SHOPPING" -> addFilter("themes", "shopping", "쇼핑", "쇼핑 접근성이 좋은 숙소를 우선 노출해요.")
            "CULTURE" -> addFilter("themes", "nature", "자연", "전시/문화 일정은 도보 동선이 편한 관광권역 숙소가 유리해요.")
            "ATTRACTION" -> addFilter("themes", "nature", "자연", "관광 명소 중심 일정이라 이동 동선이 좋은 권역을 우선 추천해요.")
            "RELAX" -> {
                addFilter("amenities", "spa", "스파/사우나", "휴양형 요청이라 릴랙스 편의시설을 우선 추천해요.")
                addFilter("amenities", "pool", "수영장", "휴양 일정에서 선호되는 수영장 옵션을 함께 적용해요.")
            }

            "ACTIVITY" -> addFilter("amenities", "gym", "체육관/피트니스", "액티비티 성향 요청이라 운동 시설을 우선 추천해요.")
            "BUSINESS" -> {
                addFilter("themes", "business", "비즈니스", "출장/업무형 일정이라 교통과 업무 동선 중심으로 좁혀요.")
                addFilter("amenities", "frontdesk_24h", "24시간 프런트데스크", "업무 일정은 체크인 유연성이 중요해요.")
            }
            else -> Unit
        }

        topAttractionFilter(retrievalHits, slots.city)?.let { attraction ->
            addFilter(
                key = "nearby_attractions",
                value = attraction.poiId.toString(),
                label = "명소 근처 (${attraction.title})",
                reason = "대화에서 언급된 명소 근처 숙소를 우선 탐색할 수 있어요.",
                score = 2,
            )
        }

        when (slots.companions) {
            "FAMILY" -> addFilter("family_options", "kid_free_stay", "아동 무료 투숙 가능", "가족 여행 요청이라 아동 혜택 옵션을 우선 적용해요.")
            "COUPLE" -> addFilter("themes", "romance", "커플", "커플 여행 성향에 맞는 숙소를 우선 노출해요.")
            "FRIENDS" -> addFilter("themes", "group", "그룹/단체", "동행 인원 성격상 단체 친화형 숙소를 우선 추천해요.")
            "SOLO" -> addFilter("themes", "business", "비즈니스", "1인 여행은 접근성/동선이 좋은 숙소 우선 추천이 유리해요.")
            else -> Unit
        }

        if (normalized.contains("조식")) {
            addFilter("amenities", "breakfast", "조식 포함", "조식 선호 조건을 반영했어요.")
        }
        if (normalized.contains("주차")) {
            addFilter("amenities", "parking", "주차", "주차 가능 숙소 위주로 좁혀볼 수 있어요.")
        }
        if (normalized.contains("오션뷰") || normalized.contains("바다") || normalized.contains("해변")) {
            addFilter("amenities", "ocean_view", "오션뷰", "바다/해변 뷰 선호를 반영했어요.")
            addFilter("beach_options", "beach_nearby", "전용 해변", "해변 근접 숙소를 우선 조회해요.")
        }
        if (normalized.contains("수영장")) {
            addFilter("amenities", "pool", "수영장", "수영장 편의시설 조건을 반영했어요.")
        }
        if (normalized.contains("프라이빗 풀") || normalized.contains("개인 수영장") || normalized.contains("private pool")) {
            addFilter("amenities", "private_pool", "전용 수영장", "프라이빗 풀 수요를 반영했어요.")
        }
        if (normalized.contains("반려") || normalized.contains("애견") || normalized.contains("pet")) {
            addFilter("themes", "pet", "반려동물 동반 가능", "반려동물 동반 숙소 중심으로 추천해요.")
            addFilter("amenities", "pet_friendly", "반려동물 동반 가능", "펫 프렌들리 편의시설을 함께 적용해요.")
        }
        if (normalized.contains("공항")) {
            addFilter("amenities", "airport_transfer", "공항 이동 교통편 서비스", "공항 이동 편의성을 고려해 필터를 제안해요.")
        }
        if (normalized.contains("무료 취소") || normalized.contains("무료취소") || normalized.contains("취소 가능")) {
            addFilter("payment_options", "free_cancel", "예약 무료 취소", "취소 유연성이 중요하다고 판단해 정책 필터를 추가했어요.")
        }
        if (normalized.contains("후지불") || normalized.contains("나중 결제") || normalized.contains("나중결제")) {
            addFilter("payment_options", "reserve_now_pay_later", "선예약 후지불", "결제 시점 유연성을 위해 후지불 옵션을 우선 제안해요.")
        }

        val budget = slots.budgetKrw
        if (budget != null && budget > 0) {
            val nights = ((slots.days ?: 2) - 1).coerceAtLeast(1)
            val maxPerNight = (budget / nights).coerceAtLeast(50_000)
            addFilter(
                key = "max_price",
                value = maxPerNight.toString(),
                label = "1박 최대 ${toWon(maxPerNight)}",
                reason = "입력한 예산 기준으로 1박 최대 금액을 자동 계산해 적용해요.",
            )
        }

        val sortHint = recommendSortHint(normalized, slots)
        sortHint?.let { hint ->
            addFilter(
                key = "sort",
                value = hint.value,
                label = hint.label,
                reason = hint.reason,
                score = 2,
            )
        }

        profileHints(profile).forEach { hint ->
            val added = addFilter(
                key = hint.key,
                value = hint.value,
                label = hint.label,
                reason = "이전 선호(${hint.tag}) 패턴이 확인되어 함께 추천해요.",
                source = "profile",
                score = 2,
            )
            if (added) {
                profileApplied = true
            }
        }

        val limited = filters.take(6)
        val confidence = confidenceScore(
            filterCount = limited.size,
            signalScore = signalScore,
            profileApplied = profileApplied,
            slots = slots,
        )
        val rationale = limited.map { it.reason }.distinct().take(3)
        val clarifyQuestions = buildClarifyQuestions(
            normalizedMessage = normalized,
            slots = slots,
            filters = limited,
            confidence = confidence,
        )
        val missingSlots = detectMissingSlots(
            normalizedMessage = normalized,
            slots = slots,
            filters = limited,
        )
        val clarifyRequired = missingSlots.isNotEmpty() || confidence < 0.45
        val recommendedSourceTypes = recommendSourceTypes(
            slots = slots,
            retrievalHits = retrievalHits,
        )
        val sourceHints = buildSourceHints(
            slots = slots,
            recommendedSourceTypes = recommendedSourceTypes,
        )
        val clarifyActions = buildClarifyActions(
            missingSlots = missingSlots,
            slots = slots,
            recommendedSourceTypes = recommendedSourceTypes,
        )
        val summary = if (limited.isEmpty()) {
            "현재 입력 조건만으로 검색을 진행해도 됩니다. 예산/선호를 더 알려주시면 필터를 자동 추천해 드릴게요."
        } else if (profileApplied) {
            "AI가 최근 선호 패턴과 현재 대화를 함께 분석해 검색 필터 ${limited.size}개를 제안했어요."
        } else {
            "AI가 대화 내용을 바탕으로 검색 필터 ${limited.size}개를 제안했어요. 필요하면 일부만 선택해서 적용하세요."
        }

        meterRegistry.counter(
            "chat_search_handoff_total",
            "result",
            if (limited.isEmpty()) "empty" else "filters",
        ).increment()
        meterRegistry.summary("chat_search_handoff_filter_count").record(limited.size.toDouble())
        meterRegistry.summary("chat_search_handoff_confidence").record(confidence)
        meterRegistry.summary("chat_search_handoff_clarify_question_count").record(clarifyQuestions.size.toDouble())
        meterRegistry.summary("chat_search_handoff_missing_slot_count").record(missingSlots.size.toDouble())
        meterRegistry.summary("chat_search_handoff_clarify_action_count").record(clarifyActions.size.toDouble())
        meterRegistry.summary("chat_search_handoff_source_type_count").record(recommendedSourceTypes.size.toDouble())
        meterRegistry.summary("chat_search_handoff_source_hint_count").record(sourceHints.size.toDouble())
        if (profileApplied) {
            meterRegistry.counter("chat_search_handoff_profile_applied_total").increment()
        }
        meterRegistry.counter(
            "chat_search_handoff_clarify_required_total",
            "required",
            clarifyRequired.toString(),
        ).increment()
        if (clarifyQuestions.isNotEmpty()) {
            meterRegistry.counter("chat_search_handoff_clarify_suggested_total").increment()
        }
        clarifyActions.forEach { action ->
            meterRegistry.counter(
                "chat_search_handoff_clarify_action_total",
                "slot",
                action.slot,
            ).increment()
        }
        sortHint?.let { hint ->
            meterRegistry.counter(
                "chat_search_handoff_sort_hint_total",
                "sort",
                hint.value,
            ).increment()
        }

        return ChatSearchHandoffRecommendation(
            summary = summary,
            recommended_filters = limited,
            confidence = confidence,
            profile_applied = profileApplied,
            rationale = rationale,
            clarify_questions = clarifyQuestions,
            clarify_actions = clarifyActions,
            clarify_required = clarifyRequired,
            missing_slots = missingSlots,
            sort_hint = sortHint,
            city = slots.city,
            days = slots.days,
            companions = slots.companions,
            recommended_source_types = recommendedSourceTypes,
            recommended_source_hints = sourceHints,
            search_patch = ChatSearchHandoffSearchPatch(
                city = slots.city,
                days = slots.days,
                companions = slots.companions,
            ),
        )
    }

    private fun buildClarifyQuestions(
        normalizedMessage: String,
        slots: ChatSlots,
        filters: List<ChatSearchHandoffFilter>,
        confidence: Double,
    ): List<String> {
        val questions = mutableListOf<String>()
        if (slots.city.isNullOrBlank()) {
            questions += "어느 도시로 찾을까요? (예: 서울, 부산, 제주)"
        }
        if (slots.days == null) {
            questions += "여행 일정은 몇 박 며칠로 볼까요?"
        }
        if (slots.companions == null) {
            questions += "동행 유형을 알려주세요. (가족/커플/친구/혼자)"
        }
        if (slots.budgetKrw == null && filters.none { it.key == "max_price" }) {
            questions += "1박 예산 상한을 알려주시면 가격대를 바로 맞출 수 있어요."
        }
        val hasAmenitySignal = listOf("조식", "주차", "수영장", "스파", "오션뷰", "무료 취소", "후지불")
            .any { normalizedMessage.contains(it.lowercase()) }
        if (!hasAmenitySignal) {
            questions += "필수 옵션이 있나요? (조식/주차/무료취소/수영장)"
        }
        if (slots.intent == "BUSINESS") {
            questions += "출장 일정이라면 체크인 시간/교통(공항·역세권) 우선순위를 알려주세요."
        }
        if (slots.intent == "FOOD" && filters.none { it.key == "payment_options" }) {
            questions += "맛집 중심이면 무료취소/후지불 같은 결제 정책도 함께 맞춰드릴까요?"
        }
        if (confidence < 0.45 && questions.isEmpty()) {
            questions += "우선순위(가격/위치/후기) 중 무엇이 가장 중요한가요?"
        }
        return questions.distinct().take(3)
    }

    private fun detectMissingSlots(
        normalizedMessage: String,
        slots: ChatSlots,
        filters: List<ChatSearchHandoffFilter>,
    ): List<String> {
        val missing = mutableListOf<String>()

        if (slots.city.isNullOrBlank()) {
            missing += "city"
        }
        if (slots.days == null) {
            missing += "days"
        }
        if (slots.companions == null) {
            missing += "companions"
        }
        if (slots.budgetKrw == null && filters.none { it.key == "max_price" }) {
            missing += "budget"
        }

        val hasPreferenceSignal = listOf(
            "조식",
            "주차",
            "수영장",
            "스파",
            "오션뷰",
            "무료 취소",
            "후지불",
            "맛집",
            "관광",
            "쇼핑",
            "비즈니스",
            "가족",
            "커플",
        ).any { normalizedMessage.contains(it.lowercase()) }

        if (!hasPreferenceSignal && filters.none { it.key in setOf("amenities", "themes", "payment_options", "family_options", "beach_options") }) {
            missing += "preferences"
        }

        return missing
    }

    private fun buildClarifyActions(
        missingSlots: List<String>,
        slots: ChatSlots,
        recommendedSourceTypes: List<String>,
    ): List<ChatSearchHandoffClarifyAction> {
        if (missingSlots.isEmpty()) {
            return emptyList()
        }

        val defaultScope = recommendedSourceTypes
            .map { it.uppercase() }
            .filter { it in validSourceTypes }
            .ifEmpty { listOf("PROPERTY", "POI") }
        val stayScope = listOf("PROPERTY", "PACKAGE")

        val actions = mutableListOf<ChatSearchHandoffClarifyAction>()

        fun add(
            slot: String,
            label: String,
            prompt: String,
            patch: ChatSearchHandoffSearchPatch = ChatSearchHandoffSearchPatch(),
            sourceTypes: List<String> = defaultScope,
        ) {
            actions += ChatSearchHandoffClarifyAction(
                slot = slot,
                label = label,
                prompt = prompt,
                search_patch = patch,
                recommended_source_types = sourceTypes,
            )
        }

        if ("city" in missingSlots) {
            add(
                slot = "city",
                label = "서울",
                prompt = "서울 기준으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = "Seoul", days = slots.days, companions = slots.companions),
            )
            add(
                slot = "city",
                label = "부산",
                prompt = "부산 기준으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = "Busan", days = slots.days, companions = slots.companions),
            )
            add(
                slot = "city",
                label = "제주",
                prompt = "제주 기준으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = "Jeju", days = slots.days, companions = slots.companions),
            )
        }

        if ("days" in missingSlots) {
            val cityLabel = slots.city ?: "도시"
            add(
                slot = "days",
                label = "1박 2일",
                prompt = "$cityLabel 1박 2일 일정으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = slots.city, days = 2, companions = slots.companions),
                sourceTypes = stayScope,
            )
            add(
                slot = "days",
                label = "2박 3일",
                prompt = "$cityLabel 2박 3일 일정으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = slots.city, days = 3, companions = slots.companions),
                sourceTypes = stayScope,
            )
            add(
                slot = "days",
                label = "3박 4일",
                prompt = "$cityLabel 3박 4일 일정으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = slots.city, days = 4, companions = slots.companions),
                sourceTypes = stayScope,
            )
        }

        if ("companions" in missingSlots) {
            val cityLabel = slots.city ?: "도시"
            add(
                slot = "companions",
                label = "가족 여행",
                prompt = "$cityLabel 가족 여행 기준으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = slots.city, days = slots.days, companions = "FAMILY"),
                sourceTypes = stayScope,
            )
            add(
                slot = "companions",
                label = "커플 여행",
                prompt = "$cityLabel 커플 여행 기준으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = slots.city, days = slots.days, companions = "COUPLE"),
                sourceTypes = stayScope,
            )
            add(
                slot = "companions",
                label = "친구 여행",
                prompt = "$cityLabel 친구 여행 기준으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = slots.city, days = slots.days, companions = "FRIENDS"),
                sourceTypes = stayScope,
            )
            add(
                slot = "companions",
                label = "혼자 여행",
                prompt = "$cityLabel 혼자 여행 기준으로 추천해줘",
                patch = ChatSearchHandoffSearchPatch(city = slots.city, days = slots.days, companions = "SOLO"),
                sourceTypes = stayScope,
            )
        }

        if ("budget" in missingSlots) {
            val cityLabel = slots.city ?: "도시"
            add(
                slot = "budget",
                label = "1박 10만원대",
                prompt = "$cityLabel 1박 10만원대 숙소 위주로 추천해줘",
                sourceTypes = stayScope,
            )
            add(
                slot = "budget",
                label = "1박 20만원대",
                prompt = "$cityLabel 1박 20만원대 숙소 위주로 추천해줘",
                sourceTypes = stayScope,
            )
            add(
                slot = "budget",
                label = "가격보다 품질 우선",
                prompt = "$cityLabel 가격보다 품질/후기 우선으로 추천해줘",
                sourceTypes = stayScope,
            )
        }

        if ("preferences" in missingSlots) {
            val cityLabel = slots.city ?: "도시"
            add(slot = "preferences", label = "가성비 우선", prompt = "$cityLabel 가성비 좋은 숙소를 가격 낮은순으로 추천해줘", sourceTypes = stayScope)
            add(slot = "preferences", label = "후기 우선", prompt = "$cityLabel 후기 평점 높은 숙소 위주로 추천해줘", sourceTypes = stayScope)
            add(slot = "preferences", label = "이동 편의 우선", prompt = "$cityLabel 이동이 편한 숙소를 거리순으로 추천해줘", sourceTypes = stayScope)
            add(slot = "preferences", label = "조식 포함", prompt = "$cityLabel 조식 포함 숙소 위주로 추천해줘", sourceTypes = stayScope)
            add(slot = "preferences", label = "주차 가능", prompt = "$cityLabel 주차 가능한 숙소 위주로 추천해줘", sourceTypes = stayScope)
            add(slot = "preferences", label = "무료 취소", prompt = "$cityLabel 예약 무료 취소 가능한 숙소 위주로 추천해줘", sourceTypes = stayScope)
            add(slot = "preferences", label = "수영장 포함", prompt = "$cityLabel 수영장 있는 숙소 위주로 추천해줘", sourceTypes = stayScope)
        }

        return actions
            .distinctBy { "${it.slot}:${it.label}" }
            .take(8)
    }

    private fun profileHints(profile: PreferenceProfileSnapshot): List<ProfileHint> {
        return profile.tagWeights.entries
            .mapNotNull { (rawTag, weight) ->
                if (weight < 2) return@mapNotNull null
                val normalizedTag = rawTag.trim().lowercase().replace(" ", "_")
                val templates = PROFILE_TAG_HINTS[normalizedTag] ?: return@mapNotNull null
                templates.map { template ->
                    ProfileHint(
                        tag = normalizedTag,
                        weight = weight,
                        key = template.key,
                        value = template.value,
                        label = template.label,
                    )
                }
            }
            .flatten()
            .sortedByDescending { it.weight }
            .distinctBy { "${it.key}:${it.value}" }
            .take(3)
    }

    private fun confidenceScore(
        filterCount: Int,
        signalScore: Int,
        profileApplied: Boolean,
        slots: ChatSlots,
    ): Double {
        if (filterCount == 0) {
            return 0.24
        }
        var score = 0.34 + (filterCount * 0.08) + (signalScore * 0.04)
        if (!slots.city.isNullOrBlank()) {
            score += 0.05
        }
        if (slots.days != null) {
            score += 0.04
        }
        if (profileApplied) {
            score += 0.06
        }
        val bounded = score.coerceIn(0.28, 0.96)
        return kotlin.math.round(bounded * 100.0) / 100.0
    }

    private fun toWon(value: Long): String {
        return "₩${value.toString().reversed().chunked(3).joinToString(",").reversed()}"
    }

    private fun recommendSortHint(
        normalizedMessage: String,
        slots: ChatSlots,
    ): ChatSearchHandoffSortHint? {
        return when {
            containsAny(normalizedMessage, listOf("가성비", "저렴", "싼", "cheap", "budget", "최저가", "가격 낮")) -> {
                ChatSearchHandoffSortHint(
                    value = "price_asc",
                    label = "가격 낮은순",
                    reason = "가성비/예산 중심 요청이라 가격 낮은순 정렬을 추천해요.",
                )
            }

            containsAny(normalizedMessage, listOf("평점", "리뷰", "후기", "quality", "고급", "프리미엄", "럭셔리")) -> {
                ChatSearchHandoffSortHint(
                    value = "rating_desc",
                    label = "평점 높은순",
                    reason = "품질/후기 중심 요청이라 평점 높은순 정렬을 추천해요.",
                )
            }

            containsAny(normalizedMessage, listOf("가까운", "근처", "도보", "역세권", "거리", "이동", "교통")) -> {
                ChatSearchHandoffSortHint(
                    value = "distance",
                    label = "거리순",
                    reason = "이동 동선이 중요해 보여 거리순 정렬을 추천해요.",
                )
            }

            slots.intent == "BUSINESS" -> {
                ChatSearchHandoffSortHint(
                    value = "distance",
                    label = "거리순",
                    reason = "출장 일정은 이동 동선이 중요해 거리순 정렬을 우선 제안해요.",
                )
            }

            slots.intent == "RELAX" -> {
                ChatSearchHandoffSortHint(
                    value = "rating_desc",
                    label = "평점 높은순",
                    reason = "휴양 일정은 체감 만족도가 중요해 평점 높은순 정렬을 추천해요.",
                )
            }

            else -> null
        }
    }

    private fun containsAny(
        message: String,
        keywords: Collection<String>,
    ): Boolean {
        return keywords.any { keyword -> message.contains(keyword.lowercase()) }
    }

    private data class ProfileHint(
        val tag: String,
        val weight: Int,
        val key: String,
        val value: String,
        val label: String,
    )

    private data class ProfileHintTemplate(
        val key: String,
        val value: String,
        val label: String,
    )

    private data class AttractionCandidate(
        val poiId: Long,
        val title: String,
    )

    private fun topAttractionFilter(
        retrievalHits: List<RagHit>,
        requestedCity: String?,
    ): AttractionCandidate? {
        return retrievalHits
            .asSequence()
            .filter { it.document.sourceType.equals("POI", ignoreCase = true) }
            .mapNotNull { hit ->
                val hitCity = hit.document.metadata["city"]?.toString()?.trim()
                if (!requestedCity.isNullOrBlank() && !hitCity.isNullOrBlank() && !hitCity.equals(requestedCity, ignoreCase = true)) {
                    return@mapNotNull null
                }
                parseDocId(hit.document.docId)?.let { poiId ->
                    AttractionCandidate(
                        poiId = poiId,
                        title = hit.document.title.trim().ifBlank { "인기 명소" },
                    )
                }
            }
            .firstOrNull()
    }

    private fun parseDocId(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return raw.substringAfter(':', raw).toLongOrNull()
    }

    private fun recommendSourceTypes(
        slots: ChatSlots,
        retrievalHits: List<RagHit>,
    ): List<String> {
        val ordered = linkedSetOf<String>()

        if (slots.sourceTypes.isNotEmpty()) {
            slots.sourceTypes
                .asSequence()
                .map { it.uppercase() }
                .filter { it in validSourceTypes }
                .forEach { ordered += it }
        }

        when (slots.intent) {
            "FOOD", "SHOPPING", "CULTURE", "ATTRACTION" -> {
                ordered += "POI"
                ordered += "PROPERTY"
            }
            "BUSINESS", "RELAX", "ACTIVITY" -> {
                ordered += "PROPERTY"
                ordered += "PACKAGE"
            }
            else -> {
                ordered += "PROPERTY"
                ordered += "POI"
            }
        }

        retrievalHits
            .asSequence()
            .map { it.document.sourceType.uppercase() }
            .filter { it in validSourceTypes }
            .forEach { ordered += it }

        return ordered.take(3)
    }

    private fun buildSourceHints(
        slots: ChatSlots,
        recommendedSourceTypes: List<String>,
    ): List<ChatSearchHandoffSourceHint> {
        val city = slots.city ?: "Seoul"
        return recommendedSourceTypes.mapNotNull { sourceType ->
            when (sourceType.uppercase()) {
                "PROPERTY" -> ChatSearchHandoffSourceHint(
                    source_type = "PROPERTY",
                    label = "숙소 중심",
                    reason = when (slots.intent) {
                        "BUSINESS" -> "출장/업무 일정은 숙소 접근성과 체크인 편의가 중요해요."
                        "RELAX" -> "휴양 일정은 숙소 편의시설 중심 탐색이 효율적이에요."
                        else -> "예약 전환 가능성이 높은 숙소 결과를 우선 확인할 수 있어요."
                    },
                    prompt = "$city 숙소 중심으로 추천해줘",
                )

                "PACKAGE" -> ChatSearchHandoffSourceHint(
                    source_type = "PACKAGE",
                    label = "패키지 중심",
                    reason = "숙소+혜택 번들을 같이 비교하면 총비용을 줄이기 좋아요.",
                    prompt = "$city 패키지 중심으로 추천해줘",
                )

                "TICKET" -> ChatSearchHandoffSourceHint(
                    source_type = "TICKET",
                    label = "티켓 중심",
                    reason = "입장권/전시/액티비티는 티켓 소스가 가장 빠르게 조건 매칭돼요.",
                    prompt = "$city 티켓 중심으로 추천해줘",
                )

                "POI" -> ChatSearchHandoffSourceHint(
                    source_type = "POI",
                    label = "주변 추천 중심",
                    reason = when (slots.intent) {
                        "FOOD" -> "맛집 요청은 POI 소스에서 지역 밀집도를 잘 반영해요."
                        "SHOPPING" -> "쇼핑 동선은 주변 상권 POI 기반으로 보는 게 정확해요."
                        "ATTRACTION", "CULTURE" -> "관광/명소 동선은 POI 결과가 우선순위를 잘 잡아줘요."
                        else -> "숙소 주변 명소/맛집까지 함께 고려할 수 있어요."
                    },
                    prompt = "$city 주변 명소와 맛집 중심으로 추천해줘",
                )

                else -> null
            }
        }
    }

    companion object {
        private val PROFILE_TAG_HINTS: Map<String, List<ProfileHintTemplate>> = mapOf(
            "family" to listOf(
                ProfileHintTemplate("family_options", "kid_free_stay", "아동 무료 투숙 가능"),
                ProfileHintTemplate("themes", "family", "가족 여행객 친화형"),
            ),
            "couple" to listOf(
                ProfileHintTemplate("themes", "romance", "커플"),
            ),
            "food" to listOf(
                ProfileHintTemplate("amenities", "restaurant", "레스토랑"),
            ),
            "nature" to listOf(
                ProfileHintTemplate("themes", "nature", "자연"),
            ),
            "activity" to listOf(
                ProfileHintTemplate("amenities", "gym", "체육관/피트니스"),
            ),
            "pet" to listOf(
                ProfileHintTemplate("themes", "pet", "반려동물 동반 가능"),
                ProfileHintTemplate("amenities", "pet_friendly", "반려동물 동반 가능"),
            ),
            "business" to listOf(
                ProfileHintTemplate("themes", "business", "비즈니스"),
            ),
        )
    }
}

data class ChatSearchHandoffRecommendation(
    val summary: String,
    val recommended_filters: List<ChatSearchHandoffFilter>,
    val confidence: Double,
    val profile_applied: Boolean,
    val rationale: List<String>,
    val clarify_questions: List<String> = emptyList(),
    val clarify_actions: List<ChatSearchHandoffClarifyAction> = emptyList(),
    val clarify_required: Boolean = false,
    val missing_slots: List<String> = emptyList(),
    val sort_hint: ChatSearchHandoffSortHint? = null,
    val city: String? = null,
    val days: Int? = null,
    val companions: String? = null,
    val recommended_source_types: List<String> = emptyList(),
    val recommended_source_hints: List<ChatSearchHandoffSourceHint> = emptyList(),
    val search_patch: ChatSearchHandoffSearchPatch = ChatSearchHandoffSearchPatch(),
)

data class ChatSearchHandoffSearchPatch(
    val city: String? = null,
    val days: Int? = null,
    val companions: String? = null,
)

data class ChatSearchHandoffFilter(
    val key: String,
    val value: String,
    val label: String,
    val reason: String,
    val source: String = "rule",
)

data class ChatSearchHandoffClarifyAction(
    val slot: String,
    val label: String,
    val prompt: String,
    val search_patch: ChatSearchHandoffSearchPatch = ChatSearchHandoffSearchPatch(),
    val recommended_source_types: List<String> = emptyList(),
)

data class ChatSearchHandoffSortHint(
    val value: String,
    val label: String,
    val reason: String,
)

data class ChatSearchHandoffSourceHint(
    val source_type: String,
    val label: String,
    val reason: String,
    val prompt: String,
)
