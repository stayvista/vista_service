package com.devoceanblue.stayvista.domain.chat

import org.junit.jupiter.api.Assertions.assertEquals
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
}
