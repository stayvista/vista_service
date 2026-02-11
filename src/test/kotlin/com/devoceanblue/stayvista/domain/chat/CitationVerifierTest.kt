package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CitationVerifierTest {
    private val verifier = CitationVerifier(SimpleMeterRegistry())

    @Test
    fun `verifyOrMitigate should block assertive answer without source`() {
        val result = ChatRecommendData(
            answer = "이 상품은 재고가 항상 충분하고 가격은 120000원으로 확정입니다.",
            assistant_text = "이 상품은 재고가 항상 충분하고 가격은 120000원으로 확정입니다.",
            cards = emptyList(),
            followups = emptyList(),
            context_used = emptyMap(),
            llm_used = true,
            sources = emptyList(),
        )

        val verified = verifier.verifyOrMitigate(result)
        assertFalse(verified.llm_used)
        assertTrue(verified.context_used.containsKey("citation_guard"))
    }

    @Test
    fun `verifyOrMitigate should keep assertive answer when citation exists`() {
        val source = ChatSource(
            doc_id = "ticket:10",
            title = "테스트 티켓",
            snippet = "재고 20",
            source_type = "TICKET",
        )
        val result = ChatRecommendData(
            answer = "가격은 120000원이고 재고가 있습니다.",
            assistant_text = "가격은 120000원이고 재고가 있습니다.",
            cards = listOf(
                ChatCard(
                    type = "TICKET",
                    id = "ticket:10",
                    title = "테스트 티켓",
                    why = "재고 확인",
                    source = listOf(source),
                ),
            ),
            followups = emptyList(),
            context_used = emptyMap(),
            llm_used = true,
            sources = listOf(source),
        )

        val verified = verifier.verifyOrMitigate(result)
        assertTrue(verified.llm_used)
        assertEquals(1, verified.sources.size)
    }
}
