package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatSearchHandoffAdvisorTest {
    private val advisor = ChatSearchHandoffAdvisor(SimpleMeterRegistry())

    @Test
    fun `recommend should include family and breakfast filters from slots and message`() {
        val result = advisor.recommend(
            message = "서울 가족 여행, 조식 포함 숙소 추천해줘",
            slots = ChatSlots(
                city = "Seoul",
                days = 3,
                budgetKrw = 420_000,
                companions = "FAMILY",
                intent = "GENERAL",
            ),
        )

        assertTrue(result.recommended_filters.any { it.key == "family_options" && it.value == "kid_free_stay" })
        assertTrue(result.recommended_filters.any { it.key == "amenities" && it.value == "breakfast" })
        assertTrue(result.recommended_filters.any { it.key == "max_price" })
        assertTrue(result.confidence >= 0.5)
        assertFalse(result.profile_applied)
        assertEquals("Seoul", result.city)
        assertEquals(3, result.days)
        assertEquals("FAMILY", result.companions)
        assertEquals("Seoul", result.search_patch.city)
        assertEquals(3, result.search_patch.days)
        assertEquals("FAMILY", result.search_patch.companions)
        assertTrue(result.recommended_source_types.contains("PROPERTY"))
        assertFalse(result.clarify_required)
        assertTrue(result.missing_slots.isEmpty())
        assertTrue(result.clarify_questions.size <= 3)
        assertTrue(result.clarify_actions.isEmpty())
        assertTrue(result.recommended_source_hints.any { it.source_type == "PROPERTY" })
    }

    @Test
    fun `recommend should include food and payment filters when requested`() {
        val result = advisor.recommend(
            message = "부산 맛집 위주로 예약 무료 취소 되는 곳 추천해줘",
            slots = ChatSlots(
                city = "Busan",
                days = null,
                budgetKrw = null,
                companions = "COUPLE",
                intent = "FOOD",
            ),
        )

        assertTrue(result.recommended_filters.any { it.key == "amenities" && it.value == "restaurant" })
        assertTrue(result.recommended_filters.any { it.key == "payment_options" && it.value == "free_cancel" })
        assertTrue(result.recommended_filters.any { it.key == "themes" && it.value == "romance" })
        assertTrue(result.rationale.isNotEmpty())
        assertEquals("Busan", result.city)
        assertEquals("COUPLE", result.companions)
        assertEquals("Busan", result.search_patch.city)
        assertEquals("COUPLE", result.search_patch.companions)
        assertTrue(result.recommended_source_types.firstOrNull() == "POI")
        assertTrue(result.recommended_source_hints.any { it.source_type == "POI" && it.prompt.contains("Busan") })
    }

    @Test
    fun `recommend should return empty filter list with fallback summary when no hint is present`() {
        val result = advisor.recommend(
            message = "추천해줘",
            slots = ChatSlots(
                city = "Seoul",
                days = null,
                budgetKrw = null,
                companions = null,
                intent = "GENERAL",
            ),
        )

        assertEquals(0, result.recommended_filters.size)
        assertEquals(0.24, result.confidence)
        assertTrue(result.summary.contains("현재 입력 조건만으로 검색"))
        assertTrue(result.recommended_source_types.isNotEmpty())
        assertTrue(result.clarify_required)
        assertTrue(result.missing_slots.contains("days"))
        assertTrue(result.missing_slots.contains("companions"))
        assertTrue(result.missing_slots.contains("budget"))
        assertTrue(result.clarify_questions.isNotEmpty())
        assertTrue(result.clarify_actions.isNotEmpty())
        assertTrue(result.clarify_actions.any { it.slot == "days" })
        assertTrue(result.clarify_actions.any { it.slot == "companions" })
    }

    @Test
    fun `recommend should include profile driven filters when profile signals are strong`() {
        val result = advisor.recommend(
            message = "서울 숙소 추천해줘",
            slots = ChatSlots(
                city = "Seoul",
                days = 2,
                budgetKrw = null,
                companions = null,
                intent = "GENERAL",
            ),
            profile = PreferenceProfileSnapshot(
                tagWeights = mapOf(
                    "family" to 3,
                    "food" to 2,
                ),
            ),
        )

        assertTrue(result.recommended_filters.any { it.key == "family_options" && it.value == "kid_free_stay" })
        assertTrue(result.recommended_filters.any { it.key == "amenities" && it.value == "restaurant" })
        assertTrue(result.profile_applied)
        assertTrue(result.confidence >= 0.5)
        assertTrue(result.summary.contains("최근 선호 패턴"))
        assertTrue(result.recommended_source_types.contains("PROPERTY"))
    }

    @Test
    fun `recommend should include nearby attraction filter when poi hit matches city`() {
        val retrievalHits = listOf(
            RagHit(
                document = RagDocument(
                    docId = "poi:101",
                    title = "해운대 해변",
                    snippet = "해운대 대표 명소",
                    sourceType = "POI",
                    metadata = mapOf("city" to "Busan"),
                ),
                score = 0.96,
            ),
        )

        val result = advisor.recommend(
            message = "부산 관광 명소 중심으로 추천해줘",
            slots = ChatSlots(
                city = "Busan",
                days = 2,
                budgetKrw = null,
                companions = "COUPLE",
                intent = "ATTRACTION",
            ),
            retrievalHits = retrievalHits,
        )

        assertTrue(result.recommended_filters.any { it.key == "nearby_attractions" && it.value == "101" })
        assertTrue(result.recommended_filters.any { it.label.contains("해운대 해변") })
        assertTrue(result.recommended_source_types.contains("POI"))
        assertTrue(result.clarify_required)
        assertTrue(result.missing_slots.contains("budget"))
        assertTrue(result.clarify_actions.any { it.slot == "budget" })
    }

    @Test
    fun `recommend should ignore nearby attraction filter when poi city does not match requested city`() {
        val retrievalHits = listOf(
            RagHit(
                document = RagDocument(
                    docId = "poi:77",
                    title = "N서울타워",
                    snippet = "서울 랜드마크",
                    sourceType = "POI",
                    metadata = mapOf("city" to "Seoul"),
                ),
                score = 0.94,
            ),
        )

        val result = advisor.recommend(
            message = "부산 관광 명소 추천해줘",
            slots = ChatSlots(
                city = "Busan",
                days = 2,
                budgetKrw = null,
                companions = null,
                intent = "ATTRACTION",
            ),
            retrievalHits = retrievalHits,
        )

        assertFalse(result.recommended_filters.any { it.key == "nearby_attractions" })
    }

    @Test
    fun `recommend should prioritize explicit source type scope from slots`() {
        val result = advisor.recommend(
            message = "서울 숙소보다 티켓 위주 추천해줘",
            slots = ChatSlots(
                city = "Seoul",
                days = 2,
                budgetKrw = null,
                companions = "COUPLE",
                intent = "GENERAL",
                sourceTypes = setOf("TICKET", "POI"),
            ),
        )

        assertEquals("TICKET", result.recommended_source_types.firstOrNull())
        assertTrue(result.recommended_source_types.contains("POI"))
        assertTrue(result.recommended_source_hints.any { it.source_type == "TICKET" })
    }

    @Test
    fun `recommend should add price ascending sort hint for budget sensitive request`() {
        val result = advisor.recommend(
            message = "서울 가성비 좋고 저렴한 숙소 추천해줘",
            slots = ChatSlots(
                city = "Seoul",
                days = 2,
                budgetKrw = 240_000,
                companions = "COUPLE",
                intent = "GENERAL",
            ),
        )

        assertTrue(result.recommended_filters.any { it.key == "sort" && it.value == "price_asc" })
        assertEquals("price_asc", result.sort_hint?.value)
    }

    @Test
    fun `recommend should add distance sort hint for business trip intent`() {
        val result = advisor.recommend(
            message = "부산 출장 숙소 추천해줘",
            slots = ChatSlots(
                city = "Busan",
                days = 2,
                budgetKrw = null,
                companions = "SOLO",
                intent = "BUSINESS",
            ),
        )

        assertTrue(result.recommended_filters.any { it.key == "sort" && it.value == "distance" })
        assertEquals("distance", result.sort_hint?.value)
    }
}
