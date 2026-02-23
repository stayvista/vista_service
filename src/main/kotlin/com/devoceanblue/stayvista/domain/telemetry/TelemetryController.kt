package com.devoceanblue.stayvista.domain.telemetry

import com.devoceanblue.stayvista.common.api.ApiResponses
import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/telemetry")
class TelemetryController(
    private val meterRegistry: MeterRegistry,
) {
    @PostMapping("/events")
    fun ingest(@Valid @RequestBody request: TelemetryEventRequest) = ApiResponses.ok(
        ingestInternal(request),
    )

    private fun ingestInternal(request: TelemetryEventRequest): TelemetryEventResponse {
        val eventName = request.event_name.trim().lowercase()
        if (eventName !in ALLOWED_EVENTS) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "Unsupported telemetry event: $eventName",
            )
        }

        validateHandoffPayload(request)

        val source = normalizeSource(request.source)
        val route = bucketRoute(request.route)
        val sourceTypeScope = normalizeSourceTypeScope(request.source_type_scope)
        if (sourceTypeScope == "invalid") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "source_type_scope must contain PROPERTY/TICKET/PACKAGE/POI values",
            )
        }
        val clarifySlot = normalizeClarifySlot(request.clarify_slot)
        if (clarifySlot == "invalid") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "clarify_slot must be one of city/days/companions/budget/preferences",
            )
        }
        val sortValue = normalizeSortValue(request.sort_value)
        if (eventName == "ai_widget_sort_hint_click" && sortValue == "invalid") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "sort_value must be one of best_match/price_asc/price_desc/rating_desc/distance",
            )
        }

        meterRegistry.counter("${eventName}_total").increment()
        meterRegistry.counter(
            "ai_widget_event_total",
            "event",
            eventName,
            "source",
            source,
            "route",
            route,
        ).increment()
        meterRegistry.counter(
            "ai_widget_source_scope_total",
            "event",
            eventName,
            "scope",
            sourceTypeScope,
        ).increment()

        recordOptionalMetrics(eventName, request, sourceTypeScope)
        recordClarifyActionMetrics(eventName, clarifySlot)
        recordSortHintMetrics(eventName, sortValue)

        return TelemetryEventResponse(
            accepted = true,
            event_name = eventName,
        )
    }

    private fun validateHandoffPayload(request: TelemetryEventRequest) {
        val filterCount = request.filter_count
        if (filterCount != null && filterCount !in 0..12) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "filter_count must be between 0 and 12",
            )
        }

        val confidence = request.handoff_confidence
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "handoff_confidence must be between 0 and 1",
            )
        }

        val missingSlotCount = request.missing_slot_count
        if (missingSlotCount != null && missingSlotCount !in 0..5) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "missing_slot_count must be between 0 and 5",
            )
        }
    }

    private fun recordOptionalMetrics(eventName: String, request: TelemetryEventRequest, sourceTypeScope: String) {
        if (eventName != "ai_widget_search_handoff") {
            return
        }

        request.filter_count?.let { count ->
            meterRegistry.summary("ai_widget_handoff_filter_count").record(count.toDouble())
        }
        request.handoff_confidence?.let { confidence ->
            meterRegistry.summary("ai_widget_handoff_confidence").record(confidence)
        }
        request.handoff_profile_applied?.let { applied ->
            meterRegistry.counter(
                "ai_widget_handoff_profile_applied_total",
                "applied",
                applied.toString(),
            ).increment()
        }
        request.clarify_required?.let { required ->
            meterRegistry.counter(
                "ai_widget_handoff_clarify_required_total",
                "required",
                required.toString(),
            ).increment()
        }
        request.missing_slot_count?.let { count ->
            meterRegistry.summary("ai_widget_handoff_missing_slot_count").record(count.toDouble())
        }
        meterRegistry.counter("ai_widget_handoff_scope_total", "scope", sourceTypeScope).increment()
    }

    private fun recordClarifyActionMetrics(eventName: String, clarifySlot: String) {
        if (eventName != "ai_widget_clarify_action_click") {
            return
        }
        meterRegistry.counter(
            "ai_widget_clarify_action_slot_total",
            "slot",
            clarifySlot,
        ).increment()
    }

    private fun recordSortHintMetrics(eventName: String, sortValue: String) {
        if (eventName != "ai_widget_sort_hint_click") {
            return
        }
        meterRegistry.counter(
            "ai_widget_sort_hint_total",
            "sort",
            sortValue,
        ).increment()
    }

    private fun normalizeSource(source: String?): String {
        val normalized = source?.trim()?.lowercase().orEmpty()
        return if (normalized in ALLOWED_SOURCES) normalized else "unknown"
    }

    private fun bucketRoute(route: String?): String {
        val normalized = route?.trim()?.lowercase().orEmpty()
        return when {
            normalized.contains("llm") -> "llm"
            normalized.contains("template") || normalized.contains("rag") -> "template"
            normalized.contains("clar") || normalized.contains("insufficient") || normalized.contains("city_missing") -> "clarify"
            normalized.contains("block") -> "blocked"
            else -> "unknown"
        }
    }

    private fun normalizeSourceTypeScope(rawScope: String?): String {
        val raw = rawScope?.trim().orEmpty()
        if (raw.isBlank()) {
            return "unknown"
        }
        val normalized = raw.split(',')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalized.isEmpty() || normalized.any { it !in ALLOWED_SOURCE_TYPES }) {
            return "invalid"
        }
        return SOURCE_SCOPE_ORDER
            .filter { normalized.contains(it) }
            .joinToString("+")
            .ifBlank { "invalid" }
    }

    private fun normalizeClarifySlot(rawSlot: String?): String {
        val normalized = rawSlot?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return "none"
        }
        return if (normalized in ALLOWED_CLARIFY_SLOTS) normalized else "invalid"
    }

    private fun normalizeSortValue(rawSort: String?): String {
        val normalized = rawSort?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return "none"
        }
        return if (normalized in ALLOWED_SORT_VALUES) normalized else "invalid"
    }

    companion object {
        private val ALLOWED_EVENTS = setOf(
            "ai_widget_open",
            "ai_widget_prompt_submit",
            "ai_widget_followup_click",
            "ai_widget_clarify_click",
            "ai_widget_clarify_action_click",
            "ai_widget_sort_hint_click",
            "ai_widget_filter_apply",
            "ai_widget_search_handoff",
            "ai_widget_view_results",
        )

        private val ALLOWED_SOURCES = setOf(
            "desktop",
            "fab",
            "quick_prompt",
            "text_input",
            "followup",
            "handoff_clarify",
            "filter_chip",
            "search_cta",
            "results_cta",
        )

        private val ALLOWED_SOURCE_TYPES = setOf(
            "PROPERTY",
            "TICKET",
            "PACKAGE",
            "POI",
        )

        private val SOURCE_SCOPE_ORDER = listOf(
            "PROPERTY",
            "PACKAGE",
            "TICKET",
            "POI",
        )

        private val ALLOWED_CLARIFY_SLOTS = setOf(
            "city",
            "days",
            "companions",
            "budget",
            "preferences",
        )

        private val ALLOWED_SORT_VALUES = setOf(
            "best_match",
            "price_asc",
            "price_desc",
            "rating_desc",
            "distance",
        )
    }
}

data class TelemetryEventRequest(
    @field:NotBlank
    val event_name: String,
    val source: String? = null,
    val route: String? = null,
    val source_type_scope: String? = null,
    val filter_count: Int? = null,
    val handoff_confidence: Double? = null,
    val handoff_profile_applied: Boolean? = null,
    val clarify_required: Boolean? = null,
    val missing_slot_count: Int? = null,
    val clarify_slot: String? = null,
    val sort_value: String? = null,
)

data class TelemetryEventResponse(
    val accepted: Boolean,
    val event_name: String,
)
