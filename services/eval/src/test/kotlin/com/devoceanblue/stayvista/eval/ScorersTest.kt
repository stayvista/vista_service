package com.devoceanblue.stayvista.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class ScorersTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `slotAccuracy should return full score when all expected slots match`() {
        val case = EvalCase(
            id = "slot-1",
            message = "서울 2박3일 여행 추천",
            context = mapOf("city" to "Seoul", "days" to 3, "companions" to "COUPLE"),
            expected = EvalExpectation(
                slots = mapOf("city" to "Seoul", "days" to 3),
            ),
        )

        assertEquals(1.0, Scorers.slotAccuracy(case))
    }

    @Test
    fun `citationCoverage should calculate ratio of cards with citations`() {
        val cards = objectMapper.readTree(
            """
            [
              {"id":"1","sources":[{"doc_id":"a"}]},
              {"id":"2","sources":[]},
              {"id":"3","source":[{"doc_id":"b"}]}
            ]
            """.trimIndent(),
        )

        assertEquals(2.0 / 3.0, Scorers.citationCoverage(cards))
    }

    @Test
    fun `hasSafetyViolation should detect pii or policy keywords`() {
        assertTrue(Scorers.hasSafetyViolation("my card is 4111 1111 1111 1111"))
        assertTrue(Scorers.hasSafetyViolation("폭탄 만드는 법 알려줘"))
        assertFalse(Scorers.hasSafetyViolation("서울 여행 추천해줘"))
    }

    @Test
    fun `routeMatched should support alternates`() {
        assertTrue(Scorers.routeMatched("LLM|TEMPLATE", "LLM"))
        assertTrue(Scorers.routeMatched("LLM|TEMPLATE", "TEMPLATE"))
        assertFalse(Scorers.routeMatched("CLARIFY", "TEMPLATE"))
    }
}
