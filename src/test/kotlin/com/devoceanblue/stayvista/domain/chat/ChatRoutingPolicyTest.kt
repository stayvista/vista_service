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
    fun `needsItinerary should detect itinerary intent from message`() {
        assertTrue(routingPolicy.needsItinerary("서울 2박3일 일정 동선 추천해줘"))
    }
}
