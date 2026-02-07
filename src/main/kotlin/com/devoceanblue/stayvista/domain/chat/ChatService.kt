package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class ChatService(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    fun recommend(request: ChatRecommendRequest): ChatRecommendData {
        val start = System.currentTimeMillis()
        meterRegistry.counter("chat_requests_total").increment()
        return try {
            val city = request.context["city"]?.toString() ?: extractCity(request.message)
            val poi = jdbcTemplate.query(
                """
                SELECT id, name
                FROM poi
                WHERE (? IS NULL OR city = ?)
                ORDER BY id DESC
                LIMIT 2
                """.trimIndent(),
                { rs, _ -> mapOf("poi_id" to "poi_${rs.getLong("id")}", "title" to rs.getString("name")) },
                city,
                city,
            )
            val properties = jdbcTemplate.query(
                """
                SELECT id, name
                FROM property
                WHERE status='ACTIVE' AND (? IS NULL OR city = ?)
                ORDER BY rating DESC, id DESC
                LIMIT 1
                """.trimIndent(),
                { rs, _ -> mapOf("property_id" to rs.getLong("id"), "title" to rs.getString("name")) },
                city,
                city,
            )
            val tickets = jdbcTemplate.query(
                """
                SELECT id, name
                FROM product
                WHERE status='ACTIVE' AND (? IS NULL OR city = ?)
                ORDER BY id DESC
                LIMIT 1
                """.trimIndent(),
                { rs, _ -> mapOf("product_id" to rs.getLong("id"), "title" to rs.getString("name")) },
                city,
                city,
            )

            val cards = mutableListOf<ChatCard>()
            poi.forEach { item ->
                cards += ChatCard(
                    type = "POI",
                    title = item["title"].toString(),
                    why = "근처 인기 장소",
                    poi_id = item["poi_id"].toString(),
                )
            }
            properties.firstOrNull()?.let { item ->
                cards += ChatCard(
                    type = "PROPERTY",
                    title = item["title"].toString(),
                    property_id = (item["property_id"] as Number).toLong(),
                )
            }
            tickets.firstOrNull()?.let { item ->
                cards += ChatCard(
                    type = "TICKET",
                    title = item["title"].toString(),
                    product_id = (item["product_id"] as Number).toLong(),
                )
            }

            ChatRecommendData(
                answer = buildAnswer(request, city, cards.isNotEmpty()),
                cards = cards,
                followups = listOf(
                    "숙소 위치는 도심/한적한 곳 중 어디가 좋나요?",
                    "실내 위주 일정으로 바꿔드릴까요?",
                ),
            )
        } catch (ex: RuntimeException) {
            meterRegistry.counter("chat_llm_fail_total").increment()
            throw ex
        } finally {
            val elapsed = (System.currentTimeMillis() - start).toDouble() / 1000.0
            meterRegistry.timer("chat_latency_seconds").record(java.time.Duration.ofMillis((elapsed * 1000).toLong()))
        }
    }

    private fun extractCity(message: String): String? {
        val normalized = message.lowercase()
        return when {
            normalized.contains("seoul") || normalized.contains("서울") -> "Seoul"
            normalized.contains("busan") || normalized.contains("부산") -> "Busan"
            normalized.contains("jeju") || normalized.contains("제주") -> "Jeju"
            else -> null
        }
    }

    private fun buildAnswer(request: ChatRecommendRequest, city: String?, hasCards: Boolean): String {
        if (!hasCards) {
            return "입력하신 조건에 맞는 추천 데이터를 아직 준비 중입니다. 예산/일정을 조금 더 구체화해 주세요."
        }
        return if (city != null) {
            "$city 기준으로 일정과 상품 후보를 묶어 추천했어요. 취향에 맞는 카드부터 선택해서 상세를 확인해 보세요."
        } else {
            "입력하신 내용을 바탕으로 여행 후보를 추천했어요. 도시를 알려주시면 더 정확하게 좁혀드릴 수 있어요."
        }
    }
}

data class ChatRecommendRequest(
    val message: String,
    val context: Map<String, Any?> = emptyMap(),
)

data class ChatRecommendData(
    val answer: String,
    val cards: List<ChatCard>,
    val followups: List<String>,
)

data class ChatCard(
    val type: String,
    val title: String,
    val why: String? = null,
    val poi_id: String? = null,
    val property_id: Long? = null,
    val product_id: Long? = null,
)
