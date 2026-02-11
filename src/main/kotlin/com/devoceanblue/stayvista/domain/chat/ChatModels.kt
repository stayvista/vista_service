package com.devoceanblue.stayvista.domain.chat

import jakarta.validation.constraints.NotBlank

data class ChatRecommendRequest(
    @field:NotBlank(message = "message is required")
    val message: String,
    val context: Map<String, Any?> = emptyMap(),
)

data class ChatRecommendData(
    val answer: String,
    val assistant_text: String,
    val cards: List<ChatCard>,
    val followups: List<String>,
    val context_used: Map<String, Any?>,
    val llm_used: Boolean,
    val sources: List<ChatSource> = emptyList(),
    val debug: ChatDebug? = null,
)

data class ChatCard(
    val type: String,
    val id: String? = null,
    val title: String,
    val price: String? = null,
    val why: String? = null,
    val source: List<ChatSource> = emptyList(),
    val sources: List<ChatSource> = source,
    val poi_id: String? = null,
    val property_id: Long? = null,
    val product_id: Long? = null,
    val package_id: Long? = null,
)

data class ChatSource(
    val doc_id: String,
    val title: String,
    val url: String? = null,
    val snippet: String,
    val source_type: String,
)

data class ChatDebug(
    val route: String,
    val model: String?,
    val llm_ms: Long,
    val rag_ms: Long,
    val total_ms: Long,
)

data class ChatSlots(
    val city: String?,
    val days: Int?,
    val budgetKrw: Long?,
    val companions: String?,
    val intent: String,
    val sourceTypes: Set<String> = emptySet(),
)

data class RagDocument(
    val docId: String,
    val title: String,
    val snippet: String,
    val sourceType: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class RagHit(
    val document: RagDocument,
    val score: Double,
)

data class RagSearchResult(
    val hits: List<RagHit>,
    val retrievalMs: Long,
    val usedEmbedding: Boolean,
)

enum class ChatRouteType {
    ASK_CLARIFICATION,
    TEMPLATE,
    LLM,
}

data class ChatRouteDecision(
    val type: ChatRouteType,
    val reason: String,
    val followups: List<String> = emptyList(),
)

data class StructuredCard(
    val type: String,
    val id: String?,
    val title: String,
    val price: String?,
    val why: String?,
    val source: List<ChatSource>,
)

data class StructuredLlmOutput(
    val assistantText: String,
    val cards: List<StructuredCard>,
    val followups: List<String>,
    val contextUsed: Map<String, Any?>,
    val llmUsed: Boolean,
)
