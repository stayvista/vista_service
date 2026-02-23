package com.devoceanblue.stayvista.domain.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatRoutingPolicyTest {
    private val routingPolicy = ChatRoutingPolicy()

    @Test
    fun `decide asks clarification when city is missing`() {
        val request = ChatRecommendRequest(message = "2박3일 여행 추천해줘")
        val slots = routingPolicy.extractSlots(request)
        val decision = routingPolicy.decide(request.message, slots, ragHits = emptyList())

        assertEquals(ChatRouteType.ASK_CLARIFICATION, decision.type)
    }

    @Test
    fun `decide allows template when intent is food and one source exists`() {
        val request = ChatRecommendRequest(message = "서울 맛집 추천해줘")
        val slots = routingPolicy.extractSlots(request)
        val hits = listOf(
            RagHit(
                document = RagDocument(
                    docId = "poi:1",
                    title = "food spot",
                    snippet = "snippet",
                    sourceType = "POI",
                    metadata = mapOf("city" to "Seoul"),
                ),
                score = 0.9,
            ),
        )

        val decision = routingPolicy.decide(request.message, slots, hits)
        assertEquals(ChatRouteType.TEMPLATE, decision.type)
        assertEquals("rag_is_sufficient", decision.reason)
    }

    @Test
    fun `decide asks clarification when only one non-poi source exists`() {
        val request = ChatRecommendRequest(message = "서울 전시 티켓 추천해줘")
        val slots = routingPolicy.extractSlots(request)
        val hits = listOf(
            RagHit(
                document = RagDocument(
                    docId = "package:1",
                    title = "Busan package",
                    snippet = "snippet",
                    sourceType = "PACKAGE",
                    metadata = mapOf("city" to "Busan"),
                ),
                score = 0.9,
            ),
        )

        val decision = routingPolicy.decide(request.message, slots, hits)
        assertEquals(ChatRouteType.ASK_CLARIFICATION, decision.type)
        assertEquals("insufficient_sources", decision.reason)
    }

    @Test
    fun `decide chooses llm when natural language itinerary is requested`() {
        val request = ChatRecommendRequest(message = "서울 3박4일 일정 자세히 짜줘")
        val slots = routingPolicy.extractSlots(request)
        val hits = listOf(
            RagHit(
                document = RagDocument(
                    docId = "property:1",
                    title = "test property",
                    snippet = "snippet",
                    sourceType = "PROPERTY",
                ),
                score = 0.9,
            ),
            RagHit(
                document = RagDocument(
                    docId = "poi:1",
                    title = "test poi",
                    snippet = "snippet",
                    sourceType = "POI",
                ),
                score = 0.8,
            ),
        )

        val decision = routingPolicy.decide(request.message, slots, hits)
        assertEquals(ChatRouteType.LLM, decision.type)
    }

    @Test
    fun `decide keeps template route when user asks for one place without itinerary narrative`() {
        val request = ChatRecommendRequest(message = "서울 딱 한곳만 추천해줘")
        val slots = routingPolicy.extractSlots(request)
        val hits = listOf(
            RagHit(
                document = RagDocument(
                    docId = "property:1",
                    title = "test property",
                    snippet = "snippet",
                    sourceType = "PROPERTY",
                ),
                score = 0.9,
            ),
            RagHit(
                document = RagDocument(
                    docId = "poi:1",
                    title = "test poi",
                    snippet = "snippet",
                    sourceType = "POI",
                ),
                score = 0.8,
            ),
        )

        val decision = routingPolicy.decide(request.message, slots, hits)
        assertEquals(ChatRouteType.TEMPLATE, decision.type)
        assertEquals("rag_is_sufficient", decision.reason)
    }

    @Test
    fun `decide chooses template when llm is disabled`() {
        val request = ChatRecommendRequest(message = "서울 3박4일 일정 자세히 짜줘")
        val slots = routingPolicy.extractSlots(request)
        val hits = listOf(
            RagHit(
                document = RagDocument(
                    docId = "property:1",
                    title = "test property",
                    snippet = "snippet",
                    sourceType = "PROPERTY",
                ),
                score = 0.9,
            ),
            RagHit(
                document = RagDocument(
                    docId = "poi:1",
                    title = "test poi",
                    snippet = "snippet",
                    sourceType = "POI",
                ),
                score = 0.8,
            ),
        )

        val decision = routingPolicy.decide(request.message, slots, hits, llmAllowed = false)
        assertEquals(ChatRouteType.TEMPLATE, decision.type)
        assertEquals("llm_disabled", decision.reason)
    }

    @Test
    fun `decide chooses template when rag is sufficient and no long narrative needed`() {
        val request = ChatRecommendRequest(message = "서울 숙소 추천")
        val slots = routingPolicy.extractSlots(request)
        val hits = listOf(
            RagHit(
                document = RagDocument(
                    docId = "property:1",
                    title = "test property",
                    snippet = "snippet",
                    sourceType = "PROPERTY",
                ),
                score = 0.9,
            ),
            RagHit(
                document = RagDocument(
                    docId = "package:1",
                    title = "test package",
                    snippet = "snippet",
                    sourceType = "PACKAGE",
                ),
                score = 0.8,
            ),
        )

        val decision = routingPolicy.decide(request.message, slots, hits)
        assertEquals(ChatRouteType.TEMPLATE, decision.type)
        assertEquals("rag_is_sufficient", decision.reason)
    }

    @Test
    fun `extractSlots should parse source type filter from context`() {
        val request = ChatRecommendRequest(
            message = "서울 추천",
            context = mapOf("source_types" to listOf("property", "ticket", "invalid")),
        )

        val slots = routingPolicy.extractSlots(request)
        assertEquals(setOf("PROPERTY", "TICKET"), slots.sourceTypes)
    }

    @Test
    fun `extractSlots should infer source type from message and override context scope`() {
        val request = ChatRecommendRequest(
            message = "서울 전시 티켓 추천해줘",
            context = mapOf("source_types" to listOf("property", "package")),
        )

        val slots = routingPolicy.extractSlots(request)
        assertEquals(setOf("TICKET"), slots.sourceTypes)
    }

    @Test
    fun `extractSlots should infer multiple source types from message`() {
        val request = ChatRecommendRequest(
            message = "서울 숙소랑 티켓 패키지 같이 추천해줘",
        )

        val slots = routingPolicy.extractSlots(request)
        assertEquals(setOf("PROPERTY", "PACKAGE", "TICKET"), slots.sourceTypes)
    }

    @Test
    fun `needsItinerary should detect itinerary intent from message`() {
        assertTrue(routingPolicy.needsItinerary("서울 2박3일 일정 동선 추천해줘"))
    }

    @Test
    fun `extractSlots should classify shopping intent`() {
        val slots = routingPolicy.extractSlots(ChatRecommendRequest(message = "서울 쇼핑 추천해줘"))
        assertEquals("SHOPPING", slots.intent)
    }

    @Test
    fun `extractSlots should classify attraction intent`() {
        val slots = routingPolicy.extractSlots(ChatRecommendRequest(message = "서울 관광 명소 추천해줘"))
        assertEquals("ATTRACTION", slots.intent)
    }

    @Test
    fun `extractSlots should derive days from check in and check out context`() {
        val request = ChatRecommendRequest(
            message = "도시 추천해줘",
            context = mapOf(
                "city" to "Seoul",
                "check_in" to "2026-03-01",
                "check_out" to "2026-03-04",
            ),
        )

        val slots = routingPolicy.extractSlots(request)
        assertEquals(4, slots.days)
    }

    @Test
    fun `extractSlots should infer companions from guest context`() {
        val request = ChatRecommendRequest(
            message = "추천해줘",
            context = mapOf(
                "city" to "Busan",
                "guests" to mapOf("adults" to 2, "children" to 1),
            ),
        )

        val slots = routingPolicy.extractSlots(request)
        assertEquals("FAMILY", slots.companions)
    }

    @Test
    fun `extractSlots should prioritize explicit message slot over context`() {
        val request = ChatRecommendRequest(
            message = "부산 2박3일 커플 여행 추천해줘",
            context = mapOf(
                "city" to "Seoul",
                "days" to 5,
                "companions" to "FAMILY",
            ),
        )

        val slots = routingPolicy.extractSlots(request)
        assertEquals("Busan", slots.city)
        assertEquals(3, slots.days)
        assertEquals("COUPLE", slots.companions)
    }
}
