package com.devoceanblue.stayvista.domain.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ItineraryPlannerServiceTest {
    private val planner = ItineraryPlannerService()

    @Test
    fun `plan should include source for every itinerary item`() {
        val cards = listOf(
            ChatCard(
                type = "POI",
                id = "poi:1",
                title = "한강 공원",
                why = "산책 추천",
                source = listOf(
                    ChatSource(
                        doc_id = "poi:1",
                        title = "한강 공원 문서",
                        snippet = "서울 대표 산책 코스",
                        source_type = "POI",
                    ),
                ),
            ),
            ChatCard(
                type = "TICKET",
                id = "ticket:2",
                title = "미술관 입장권",
                why = "우천 시 실내 추천",
                source = listOf(
                    ChatSource(
                        doc_id = "ticket:2",
                        title = "미술관 티켓 문서",
                        snippet = "오전 관람권",
                        source_type = "TICKET",
                    ),
                ),
            ),
        )

        val itinerary = planner.plan(cards = cards, fallbackHits = emptyList(), days = 2)

        assertEquals(6, itinerary.size)
        assertTrue(itinerary.all { it.source.doc_id.isNotBlank() })
        assertTrue(itinerary.all { it.source.snippet.isNotBlank() })
    }
}
