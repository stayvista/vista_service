package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticCacheServiceTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val semanticCacheService = SemanticCacheService(
        embedClient = FakeEmbedClient(),
        modelRegistry = LlmModelRegistry("chat-model", "", "embed-model", true),
        vectorStore = InMemoryVectorStore(meterRegistry, Clock.systemUTC(), ttlSeconds = 3_600),
        meterRegistry = meterRegistry,
        clock = Clock.systemUTC(),
        enabled = true,
        similarityThreshold = 0.90,
        maxEntries = 200,
    )

    @Test
    fun `lookup should hit for semantically similar query`() {
        val namespace = "chat-model|Seoul|CULTURE|poi:1;ticket:2"
        val response = ChatRecommendData(
            answer = "문화 일정 추천입니다.",
            assistant_text = "문화 일정 추천입니다.",
            cards = emptyList(),
            followups = emptyList(),
            context_used = mapOf("route" to "llm"),
            llm_used = true,
        )

        semanticCacheService.put(
            namespace = namespace,
            query = "서울 미술관 일정 추천",
            response = response,
            ttlSeconds = 180,
        )

        val hit = semanticCacheService.lookup(namespace, "서울 미술관 코스 추천")
        assertNotNull(hit)
        assertTrue(hit!!.context_used.containsKey("semantic_cache_hit"))
    }

    @Test
    fun `lookup should miss for unrelated query`() {
        val namespace = "chat-model|Seoul|GENERAL|poi:1"
        val response = ChatRecommendData(
            answer = "도심 산책 추천입니다.",
            assistant_text = "도심 산책 추천입니다.",
            cards = emptyList(),
            followups = emptyList(),
            context_used = mapOf("route" to "llm"),
            llm_used = true,
        )
        semanticCacheService.put(namespace, "서울 도심 산책 추천", response, 180)

        val miss = semanticCacheService.lookup(namespace, "부산 해변 액티비티 추천")
        assertNull(miss)
    }

    @Test
    fun `put should overwrite same normalized query key`() {
        val namespace = "chat-model|Seoul|GENERAL|poi:1"
        val responseA = ChatRecommendData(
            answer = "A",
            assistant_text = "A",
            cards = emptyList(),
            followups = emptyList(),
            context_used = emptyMap(),
            llm_used = true,
        )
        val responseB = responseA.copy(answer = "B", assistant_text = "B")

        semanticCacheService.put(namespace, "서울 야경 추천", responseA, 180)
        semanticCacheService.put(namespace, "서울  야경   추천", responseB, 180)

        val hit = semanticCacheService.lookup(namespace, "서울 야경 추천")
        assertNotNull(hit)
        assertEquals("B", hit!!.answer)
    }

    private class FakeEmbedClient : EmbedClient {
        override fun embed(text: String): List<Double> {
            val normalized = text.lowercase().replace(Regex("\\s+"), " ").trim()
            val seoul = if (normalized.contains("서울")) 1.0 else 0.0
            val busan = if (normalized.contains("부산")) 1.0 else 0.0
            val culture = if (normalized.contains("미술관") || normalized.contains("문화")) 1.0 else 0.0
            val itinerary = if (normalized.contains("일정") || normalized.contains("코스")) 1.0 else 0.0
            return listOf(
                seoul,
                busan,
                culture,
                itinerary,
            )
        }
    }
}
