package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper

class PreferenceProfileServiceTest {
    private val service = PreferenceProfileService(
        redisTemplate = StringRedisTemplate(),
        objectMapper = jacksonObjectMapper(),
        meterRegistry = SimpleMeterRegistry(),
        ttlSeconds = 3_600,
    )

    @Test
    fun `recordImplicitFeedback should accumulate preference tags and categories`() {
        val profileKey = "user:3003"
        service.recordImplicitFeedback(profileKey, "서울 전시 일정과 숙소 추천해줘")

        val profile = service.load(profileKey)
        assertTrue((profile.tagWeights["culture"] ?: 0) > 0)
        assertTrue((profile.categoryWeights["PROPERTY"] ?: 0) > 0)
    }

    @Test
    fun `applyExplicitFeedback should move liked category card to top in rerank`() {
        service.applyExplicitFeedback(
            ChatPreferenceFeedbackRequest(
                user_id = "9009",
                like_tags = listOf("museum", "culture"),
                like_categories = listOf("POI"),
                dislike_categories = listOf("PROPERTY"),
            ),
        )

        val sourcePoi = ChatSource(
            doc_id = "poi:11",
            title = "전시관",
            snippet = "museum culture exhibition",
            source_type = "POI",
        )
        val sourceProperty = ChatSource(
            doc_id = "property:44",
            title = "도심 호텔",
            snippet = "business district hotel",
            source_type = "PROPERTY",
        )

        val cards = listOf(
            ChatCard(
                type = "PROPERTY",
                id = "property:44",
                title = "도심 호텔",
                why = "접근성이 좋아요",
                source = listOf(sourceProperty),
            ),
            ChatCard(
                type = "POI",
                id = "poi:11",
                title = "국립 미술관 museum",
                why = "culture exhibition 중심",
                source = listOf(sourcePoi),
            ),
        )

        val reranked = service.rerank("user:9009", "서울 museum culture 일정", cards)
        assertEquals("POI", reranked.first().type)
        assertEquals("poi:11", reranked.first().id)
    }
}
