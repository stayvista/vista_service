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
    val itinerary: List<ItineraryItem> = emptyList(),
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
    val sourceTypes: Set<String> = emptySet(),
    val requestedPoiCategories: Set<String> = emptySet(),
    val filteredCandidateCount: Int = 0,
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

data class ChatCopilotOrchestrateRequest(
    @field:NotBlank(message = "message is required")
    val message: String,
    val session_state: ChatCopilotSessionState = ChatCopilotSessionState(),
    val limit: Int = 5,
)

data class ChatCopilotSessionState(
    val destination: String? = null,
    val place_id: String? = null,
    val date_range: ChatCopilotDateRange? = null,
    val guests: ChatCopilotGuests = ChatCopilotGuests(),
    val budget: ChatCopilotBudget? = null,
    val preferences: ChatCopilotPreferences = ChatCopilotPreferences(),
    val constraints: ChatCopilotConstraints = ChatCopilotConstraints(),
)

data class ChatCopilotDateRange(
    val check_in: String,
    val check_out: String,
)

data class ChatCopilotGuests(
    val rooms: Int = 1,
    val adults: Int = 2,
    val children: Int = 0,
    val children_ages: List<Int> = emptyList(),
)

data class ChatCopilotBudget(
    val min_price: Long? = null,
    val max_price: Long? = null,
    val currency: String = "KRW",
)

data class ChatCopilotPreferences(
    val amenities: List<String> = emptyList(),
    val property_type: List<String> = emptyList(),
    val districts: List<String> = emptyList(),
    val payment_options: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    val brands: List<String> = emptyList(),
    val bed_types: List<String> = emptyList(),
    val stars: List<Int> = emptyList(),
    val nearby_attractions: List<Long> = emptyList(),
    val family_options: List<String> = emptyList(),
    val beach_options: List<String> = emptyList(),
)

data class ChatCopilotConstraints(
    val sort: String? = null,
    val min_guest_rating: Double? = null,
    val min_location_rating: Double? = null,
    val max_distance_m: Int? = null,
    val bedrooms: Int? = null,
)

data class ChatCopilotOrchestrateData(
    val answer: String,
    val actions: List<ChatCopilotAction>,
    val evidence: List<ChatCopilotEvidence>,
    val confidence: Double,
    val session_state: ChatCopilotSessionState,
    val tool_trace: List<ChatCopilotToolTrace> = emptyList(),
    val degraded: Boolean = false,
    val request_id: String,
    val trace_id: String,
)

data class ChatCopilotAction(
    val type: String,
    val label: String,
    val payload: Map<String, Any?> = emptyMap(),
)

data class ChatCopilotEvidence(
    val subject: String,
    val why_recommended: List<String>,
    val cautions: List<String>,
    val source_refs: List<ChatCopilotSourceRef>,
)

data class ChatCopilotSourceRef(
    val source_type: String,
    val source_id: String,
    val title: String,
    val value: String? = null,
)

data class ChatCopilotToolTrace(
    val tool: String,
    val status: String,
    val took_ms: Long,
    val detail: Map<String, Any?> = emptyMap(),
)
