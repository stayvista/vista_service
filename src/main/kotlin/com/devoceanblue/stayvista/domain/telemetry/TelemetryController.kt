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
        if (eventName == "ai_widget_quick_fix_click" && clarifySlot == "none") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "clarify_slot is required for ai_widget_quick_fix_click",
            )
        }
        val sortValue = normalizeSortValue(request.sort_value)
        if (eventName == "ai_widget_sort_hint_click" && sortValue == "invalid") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "sort_value must be one of best_match/price_asc/price_desc/rating_desc/distance",
            )
        }
        val feedbackValue = normalizeFeedbackValue(request.feedback_value)
        if (eventName == "ai_widget_answer_feedback" && feedbackValue == "invalid") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "feedback_value must be positive or negative",
            )
        }
        val bulkAction = normalizeBulkAction(request.bulk_action)
        if (eventName == "ai_widget_filter_bulk_apply" && bulkAction == "invalid") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "bulk_action must be select_all or clear_all",
            )
        }
        val targetSourceType = normalizeTargetSourceType(request.target_source_type)
        if (eventName == "ai_widget_card_type_filter_click" && targetSourceType == "invalid") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "target_source_type must be one of ALL/PROPERTY/TICKET/PACKAGE/POI for ai_widget_card_type_filter_click",
            )
        }
        if (eventName == "ai_widget_card_type_filter_click" && targetSourceType == "none") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "target_source_type is required for ai_widget_card_type_filter_click",
            )
        }
        val autoPatchCount = request.auto_patch_count
        if (autoPatchCount != null && autoPatchCount !in 0..3) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "auto_patch_count must be between 0 and 3",
            )
        }
        if (eventName == "ai_widget_prompt_autopatch" && autoPatchCount == null) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "auto_patch_count is required for ai_widget_prompt_autopatch",
            )
        }
        val reuseRank = request.reuse_rank
        if (eventName == "ai_widget_prompt_reuse_click" && (reuseRank == null || reuseRank !in 1..5)) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "reuse_rank must be between 1 and 5 for ai_widget_prompt_reuse_click",
            )
        }
        val visibleCardCount = request.visible_card_count
        if (visibleCardCount != null && visibleCardCount !in 0..12) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "visible_card_count must be between 0 and 12",
            )
        }
        if (eventName == "ai_widget_card_type_filter_click" && visibleCardCount == null) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "visible_card_count is required for ai_widget_card_type_filter_click",
            )
        }
        val cardListState = normalizeCardListState(request.card_list_state)
        if (eventName == "ai_widget_card_list_toggle_click" && cardListState == "invalid") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "card_list_state must be expanded or collapsed for ai_widget_card_list_toggle_click",
            )
        }
        if (eventName == "ai_widget_card_list_toggle_click" && cardListState == "none") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "card_list_state is required for ai_widget_card_list_toggle_click",
            )
        }
        if (eventName == "ai_widget_card_list_toggle_click" && visibleCardCount == null) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "visible_card_count is required for ai_widget_card_list_toggle_click",
            )
        }
        val cardSaveState = normalizeCardSaveState(request.card_save_state)
        if (eventName == "ai_widget_card_save_click" && cardSaveState == "invalid") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "card_save_state must be saved or unsaved for ai_widget_card_save_click",
            )
        }
        if (eventName == "ai_widget_card_save_click" && cardSaveState == "none") {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "card_save_state is required for ai_widget_card_save_click",
            )
        }
        if (eventName == "ai_widget_card_save_click" && (targetSourceType == "none" || targetSourceType == "ALL")) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "target_source_type must be PROPERTY/TICKET/PACKAGE/POI for ai_widget_card_save_click",
            )
        }
        val savedCardCount = request.saved_card_count
        if (savedCardCount != null && savedCardCount !in 0..20) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "saved_card_count must be between 0 and 20",
            )
        }
        if (eventName == "ai_widget_card_save_click" && savedCardCount == null) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "saved_card_count is required for ai_widget_card_save_click",
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
        recordFeedbackMetrics(eventName, feedbackValue)
        recordFilterBulkMetrics(eventName, bulkAction)
        recordGenerationCancelMetrics(eventName, sourceTypeScope)
        recordQuickFixMetrics(eventName, clarifySlot, sourceTypeScope)
        recordAnswerCopyMetrics(eventName, sourceTypeScope)
        recordPromptAutopatchMetrics(eventName, autoPatchCount)
        recordPromptReuseMetrics(eventName, reuseRank, sourceTypeScope)
        recordCardTypeFilterMetrics(eventName, targetSourceType, sourceTypeScope, visibleCardCount)
        recordCardListToggleMetrics(eventName, cardListState, sourceTypeScope, visibleCardCount)
        recordCardSaveMetrics(eventName, cardSaveState, targetSourceType, sourceTypeScope, savedCardCount)

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

    private fun recordFeedbackMetrics(eventName: String, feedbackValue: String) {
        if (eventName != "ai_widget_answer_feedback") {
            return
        }
        meterRegistry.counter(
            "ai_widget_answer_feedback_total",
            "feedback",
            feedbackValue,
        ).increment()
    }

    private fun recordFilterBulkMetrics(eventName: String, bulkAction: String) {
        if (eventName != "ai_widget_filter_bulk_apply") {
            return
        }
        meterRegistry.counter(
            "ai_widget_filter_bulk_action_total",
            "action",
            bulkAction,
        ).increment()
    }

    private fun recordGenerationCancelMetrics(eventName: String, sourceTypeScope: String) {
        if (eventName != "ai_widget_generation_cancel") {
            return
        }
        meterRegistry.counter(
            "ai_widget_generation_cancel_scope_total",
            "scope",
            sourceTypeScope,
        ).increment()
    }

    private fun recordQuickFixMetrics(eventName: String, clarifySlot: String, sourceTypeScope: String) {
        if (eventName != "ai_widget_quick_fix_click") {
            return
        }
        meterRegistry.counter(
            "ai_widget_quick_fix_slot_total",
            "slot",
            clarifySlot,
        ).increment()
        meterRegistry.counter(
            "ai_widget_quick_fix_scope_total",
            "scope",
            sourceTypeScope,
        ).increment()
    }

    private fun recordAnswerCopyMetrics(eventName: String, sourceTypeScope: String) {
        if (eventName != "ai_widget_answer_copy_click") {
            return
        }
        meterRegistry.counter(
            "ai_widget_answer_copy_scope_total",
            "scope",
            sourceTypeScope,
        ).increment()
    }

    private fun recordPromptAutopatchMetrics(eventName: String, autoPatchCount: Int?) {
        if (eventName != "ai_widget_prompt_autopatch" || autoPatchCount == null) {
            return
        }
        meterRegistry.counter(
            "ai_widget_prompt_autopatch_count_total",
            "count",
            autoPatchCount.toString(),
        ).increment()
        meterRegistry.summary("ai_widget_prompt_autopatch_field_count").record(autoPatchCount.toDouble())
    }

    private fun recordPromptReuseMetrics(eventName: String, reuseRank: Int?, sourceTypeScope: String) {
        if (eventName != "ai_widget_prompt_reuse_click" || reuseRank == null) {
            return
        }
        meterRegistry.counter(
            "ai_widget_prompt_reuse_rank_total",
            "rank",
            reuseRank.toString(),
        ).increment()
        meterRegistry.counter(
            "ai_widget_prompt_reuse_scope_total",
            "scope",
            sourceTypeScope,
        ).increment()
    }

    private fun recordCardTypeFilterMetrics(
        eventName: String,
        targetSourceType: String,
        sourceTypeScope: String,
        visibleCardCount: Int?,
    ) {
        if (eventName != "ai_widget_card_type_filter_click") {
            return
        }
        meterRegistry.counter(
            "ai_widget_card_type_filter_target_total",
            "target",
            targetSourceType,
        ).increment()
        meterRegistry.counter(
            "ai_widget_card_type_filter_scope_total",
            "scope",
            sourceTypeScope,
        ).increment()
        if (visibleCardCount != null) {
            meterRegistry.summary("ai_widget_card_type_visible_count").record(visibleCardCount.toDouble())
        }
    }

    private fun recordCardListToggleMetrics(
        eventName: String,
        cardListState: String,
        sourceTypeScope: String,
        visibleCardCount: Int?,
    ) {
        if (eventName != "ai_widget_card_list_toggle_click") {
            return
        }
        meterRegistry.counter(
            "ai_widget_card_list_state_total",
            "state",
            cardListState,
        ).increment()
        meterRegistry.counter(
            "ai_widget_card_list_scope_total",
            "scope",
            sourceTypeScope,
        ).increment()
        if (visibleCardCount != null) {
            meterRegistry.summary("ai_widget_card_list_visible_count").record(visibleCardCount.toDouble())
        }
    }

    private fun recordCardSaveMetrics(
        eventName: String,
        cardSaveState: String,
        targetSourceType: String,
        sourceTypeScope: String,
        savedCardCount: Int?,
    ) {
        if (eventName != "ai_widget_card_save_click") {
            return
        }
        meterRegistry.counter(
            "ai_widget_card_save_state_total",
            "state",
            cardSaveState,
        ).increment()
        meterRegistry.counter(
            "ai_widget_card_save_source_type_total",
            "source_type",
            targetSourceType,
        ).increment()
        meterRegistry.counter(
            "ai_widget_card_save_scope_total",
            "scope",
            sourceTypeScope,
        ).increment()
        if (savedCardCount != null) {
            meterRegistry.summary("ai_widget_card_save_count").record(savedCardCount.toDouble())
        }
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

    private fun normalizeFeedbackValue(rawFeedback: String?): String {
        val normalized = rawFeedback?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return "none"
        }
        return if (normalized in ALLOWED_FEEDBACK_VALUES) normalized else "invalid"
    }

    private fun normalizeBulkAction(rawBulkAction: String?): String {
        val normalized = rawBulkAction?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return "none"
        }
        return if (normalized in ALLOWED_BULK_ACTIONS) normalized else "invalid"
    }

    private fun normalizeTargetSourceType(rawTargetSourceType: String?): String {
        val normalized = rawTargetSourceType?.trim()?.uppercase().orEmpty()
        if (normalized.isBlank()) {
            return "none"
        }
        return if (normalized in ALLOWED_TARGET_SOURCE_TYPES) normalized else "invalid"
    }

    private fun normalizeCardListState(rawCardListState: String?): String {
        val normalized = rawCardListState?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return "none"
        }
        return if (normalized in ALLOWED_CARD_LIST_STATES) normalized else "invalid"
    }

    private fun normalizeCardSaveState(rawCardSaveState: String?): String {
        val normalized = rawCardSaveState?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return "none"
        }
        return if (normalized in ALLOWED_CARD_SAVE_STATES) normalized else "invalid"
    }

    companion object {
        private val ALLOWED_EVENTS = setOf(
            "ai_widget_open",
            "ai_widget_prompt_submit",
            "ai_widget_prompt_autopatch",
            "ai_widget_prompt_reuse_click",
            "ai_widget_followup_click",
            "ai_widget_clarify_click",
            "ai_widget_clarify_action_click",
            "ai_widget_quick_fix_click",
            "ai_widget_sort_hint_click",
            "ai_widget_scope_hint_click",
            "ai_widget_filter_apply",
            "ai_widget_search_handoff",
            "ai_widget_view_results",
            "ai_widget_answer_feedback",
            "ai_widget_answer_copy_click",
            "ai_widget_card_type_filter_click",
            "ai_widget_card_list_toggle_click",
            "ai_widget_card_save_click",
            "ai_widget_regenerate_click",
            "ai_widget_search_blocked",
            "ai_widget_slot_chip_click",
            "ai_widget_filter_bulk_apply",
            "ai_widget_generation_cancel",
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
            "feedback",
            "filter_bulk",
            "quick_fix",
            "prompt_history",
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

        private val ALLOWED_FEEDBACK_VALUES = setOf(
            "positive",
            "negative",
        )

        private val ALLOWED_BULK_ACTIONS = setOf(
            "select_all",
            "clear_all",
        )

        private val ALLOWED_TARGET_SOURCE_TYPES = setOf(
            "ALL",
            "PROPERTY",
            "TICKET",
            "PACKAGE",
            "POI",
        )

        private val ALLOWED_CARD_LIST_STATES = setOf(
            "expanded",
            "collapsed",
        )

        private val ALLOWED_CARD_SAVE_STATES = setOf(
            "saved",
            "unsaved",
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
    val feedback_value: String? = null,
    val bulk_action: String? = null,
    val auto_patch_count: Int? = null,
    val reuse_rank: Int? = null,
    val visible_card_count: Int? = null,
    val target_source_type: String? = null,
    val card_list_state: String? = null,
    val card_save_state: String? = null,
    val saved_card_count: Int? = null,
)

data class TelemetryEventResponse(
    val accepted: Boolean,
    val event_name: String,
)
