package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.domain.chat.LlmClient
import com.devoceanblue.stayvista.domain.chat.LlmGenerateRequest
import com.devoceanblue.stayvista.domain.chat.LlmModelRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class SearchCopilotService(
    private val llmClient: LlmClient,
    private val modelRegistry: LlmModelRegistry,
    private val objectMapper: ObjectMapper,
    @Value("\${stayvista.search.copilot.llm-enabled:true}") private val llmEnabled: Boolean,
) {
    fun recommend(request: SearchCopilotRequest): SearchCopilotData {
        val heuristic = heuristicRecommendation(request)
        if (!llmEnabled) {
            return heuristic.copy(llm_used = false, degraded = true)
        }

        val llmResult = runCatching {
            val response = llmClient.generate(
                LlmGenerateRequest(
                    prompt = buildPrompt(request, heuristic),
                    systemPrompt = SYSTEM_PROMPT,
                    model = modelRegistry.activeModel(),
                    maxTokens = 320,
                    temperature = 0.1,
                ),
            )
            parseLlmResponse(response.text, heuristic)
        }.getOrNull()

        return llmResult ?: heuristic.copy(llm_used = false, degraded = true)
    }

    private fun heuristicRecommendation(request: SearchCopilotRequest): SearchCopilotData {
        val facets = request.facets_summary
        val current = request.current_filters
        val suggestions = mutableListOf<SearchCopilotFilter>()

        if (!current.containsKey("stars")) {
            firstFacetValue(facets, "stars")?.let { facet ->
                suggestions += SearchCopilotFilter(
                    key = "stars",
                    value = facet.key,
                    label = facet.label,
                    reason = "고평점 숙소 비중이 높아 추천합니다.",
                )
            }
        }
        if (!current.containsKey("property_type")) {
            firstFacetValue(facets, "property_types")?.let { facet ->
                suggestions += SearchCopilotFilter(
                    key = "property_type",
                    value = facet.key,
                    label = facet.label,
                    reason = "현재 도시에서 선호도가 높은 숙소 유형입니다.",
                )
            }
        }
        if (!current.containsKey("amenities")) {
            firstFacetValue(facets, "amenities")?.let { facet ->
                suggestions += SearchCopilotFilter(
                    key = "amenities",
                    value = facet.key,
                    label = facet.label,
                    reason = "후기에서 자주 선택되는 편의시설입니다.",
                )
            }
        }
        if (!current.containsKey("districts")) {
            firstFacetValue(facets, "districts")?.let { facet ->
                suggestions += SearchCopilotFilter(
                    key = "districts",
                    value = facet.key,
                    label = facet.label,
                    reason = "접근성과 주변 시설이 좋은 지역입니다.",
                )
            }
        }

        val limited = suggestions.take(4)
        val explanation = if (limited.isEmpty()) {
            "현재 필터가 이미 구체적이라 추가 추천 필터가 없습니다."
        } else {
            "도시/검색결과 분포를 기반으로 예약 가능성이 높은 필터를 추렸습니다."
        }
        return SearchCopilotData(
            recommended_filters = limited,
            explanation = explanation,
            llm_used = false,
            degraded = false,
        )
    }

    private fun buildPrompt(
        request: SearchCopilotRequest,
        heuristic: SearchCopilotData,
    ): String {
        val payload = mapOf(
            "request" to request,
            "heuristic" to heuristic,
        )
        return """
          아래 입력을 보고 검색 필터를 1~4개 추천하세요.
          결과는 반드시 JSON으로만 응답하세요.
          JSON schema:
          {
            "explanation": "string",
            "recommended_filters": [
              {"key":"string","value":"string","label":"string","reason":"string"}
            ]
          }
          입력:
          ${objectMapper.writeValueAsString(payload)}
        """.trimIndent()
    }

    private fun parseLlmResponse(raw: String, fallback: SearchCopilotData): SearchCopilotData {
        val node = runCatching { objectMapper.readTree(raw) }.getOrNull() ?: return fallback
        val explanation = node.path("explanation").asText(fallback.explanation)
        val filters = node.path("recommended_filters")
            .takeIf { it.isArray }
            ?.mapNotNull { row ->
                val key = row.path("key").asText("").trim()
                val value = row.path("value").asText("").trim()
                if (key.isBlank() || value.isBlank()) {
                    return@mapNotNull null
                }
                SearchCopilotFilter(
                    key = key,
                    value = value,
                    label = row.path("label").asText(value),
                    reason = row.path("reason").asText(null),
                )
            }
            ?.take(4)
            ?: fallback.recommended_filters

        return SearchCopilotData(
            recommended_filters = filters,
            explanation = explanation,
            llm_used = true,
            degraded = false,
        )
    }

    private fun firstFacetValue(facets: Map<String, Any?>, key: String): FacetCandidate? {
        val rows = facets[key]
        if (rows !is List<*>) {
            return null
        }
        val first = rows.firstOrNull { it is Map<*, *> } as? Map<*, *> ?: return null
        val value = first["key"]?.toString()?.takeIf { it.isNotBlank() }
            ?: first["name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return null
        val label = first["label"]?.toString()
            ?: first["name"]?.toString()
            ?: value
        return FacetCandidate(
            key = value,
            label = label,
        )
    }

    private data class FacetCandidate(
        val key: String,
        val label: String,
    )

    companion object {
        private const val SYSTEM_PROMPT = """
            You are StayVista search copilot.
            Recommend filters only from provided facet keys.
            Never claim inventory/price guarantees.
            Keep explanation short and concrete.
        """
    }
}

data class SearchCopilotRequest(
    val place_id: String? = null,
    val check_in: String? = null,
    val check_out: String? = null,
    val guests: Map<String, Any?> = emptyMap(),
    val current_filters: Map<String, Any?> = emptyMap(),
    val facets_summary: Map<String, Any?> = emptyMap(),
    val top_results_summary: List<Map<String, Any?>> = emptyList(),
)

data class SearchCopilotFilter(
    val key: String,
    val value: String,
    val label: String,
    val reason: String? = null,
)

data class SearchCopilotData(
    val recommended_filters: List<SearchCopilotFilter>,
    val explanation: String,
    val llm_used: Boolean,
    val degraded: Boolean = false,
)
