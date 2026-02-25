package com.devoceanblue.stayvista.domain.telemetry

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TelemetryControllerTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val controller = TelemetryController(meterRegistry)

    @Test
    fun `ingest should accept ai widget event and increment counters`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_open",
                source = "desktop",
                route = "TEMPLATE",
                source_type_scope = "property,poi",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_open", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_open_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_event_total")
                .tag("event", "ai_widget_open")
                .tag("source", "desktop")
                .tag("route", "template")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_source_scope_total")
                .tag("event", "ai_widget_open")
                .tag("scope", "PROPERTY+POI")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should reject unsupported event`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "unsupported_event",
                    source = "desktop",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should accept filter apply telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_filter_apply",
                source = "filter_chip",
                route = "LLM",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_filter_apply", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_filter_apply_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_event_total")
                .tag("event", "ai_widget_filter_apply")
                .tag("source", "filter_chip")
                .tag("route", "llm")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should record handoff detail metrics`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_search_handoff",
                source = "search_cta",
                route = "LLM",
                source_type_scope = "PROPERTY,PACKAGE",
                filter_count = 4,
                handoff_confidence = 0.82,
                handoff_profile_applied = true,
                clarify_required = true,
                missing_slot_count = 2,
                clarify_click_count = 2,
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_handoff_profile_applied_total")
                .tag("applied", "true")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_handoff_scope_total")
                .tag("scope", "PROPERTY+PACKAGE")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_handoff_clarify_required_total")
                .tag("required", "true")
                .counter()
                .count(),
        )
        assertTrue(meterRegistry.get("ai_widget_handoff_filter_count").summary().count() >= 1)
        assertTrue(meterRegistry.get("ai_widget_handoff_confidence").summary().count() >= 1)
        assertTrue(meterRegistry.get("ai_widget_handoff_missing_slot_count").summary().count() >= 1)
        assertTrue(meterRegistry.get("ai_widget_handoff_clarify_click_count").summary().count() >= 1)
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_handoff_clarify_click_state_total")
                .tag("state", "clicked")
                .counter()
                .count(),
        )
        assertTrue(
            meterRegistry.get("ai_widget_handoff_filter_count_by_clarify")
                .tag("state", "clicked")
                .summary()
                .count() >= 1,
        )
        assertTrue(
            meterRegistry.get("ai_widget_handoff_confidence_by_clarify")
                .tag("state", "clicked")
                .summary()
                .count() >= 1,
        )
    }

    @Test
    fun `ingest should reject invalid handoff confidence`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_search_handoff",
                    source = "search_cta",
                    handoff_confidence = 1.2,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should accept clarify click telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_clarify_click",
                source = "handoff_clarify",
                route = "CLARIFY",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_clarify_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_clarify_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_event_total")
                .tag("event", "ai_widget_clarify_click")
                .tag("source", "handoff_clarify")
                .tag("route", "clarify")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept clarify action click telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_clarify_action_click",
                source = "handoff_clarify",
                route = "CLARIFY",
                clarify_slot = "days",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_clarify_action_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_clarify_action_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_clarify_action_slot_total")
                .tag("slot", "days")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept sort hint click telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_sort_hint_click",
                source = "filter_chip",
                route = "TEMPLATE",
                sort_value = "distance",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_sort_hint_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_sort_hint_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_sort_hint_total")
                .tag("sort", "distance")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept source scope hint telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_scope_hint_click",
                source = "filter_chip",
                route = "TEMPLATE",
                source_type_scope = "POI",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_scope_hint_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_scope_hint_click_total").counter().count())
    }

    @Test
    fun `ingest should accept slot chip click telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_slot_chip_click",
                source = "handoff_clarify",
                route = "CLARIFY",
                clarify_slot = "preferences",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_slot_chip_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_slot_chip_click_total").counter().count())
    }

    @Test
    fun `ingest should accept quick fix click telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_quick_fix_click",
                source = "quick_fix",
                route = "CLARIFY",
                source_type_scope = "PROPERTY",
                clarify_slot = "budget",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_quick_fix_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_quick_fix_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_quick_fix_slot_total")
                .tag("slot", "budget")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_quick_fix_scope_total")
                .tag("scope", "PROPERTY")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept answer copy click telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_answer_copy_click",
                source = "results_cta",
                route = "LLM",
                source_type_scope = "PROPERTY,POI",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_answer_copy_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_answer_copy_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_answer_copy_scope_total")
                .tag("scope", "PROPERTY+POI")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept prompt autopatch telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_prompt_autopatch",
                source = "text_input",
                route = "LLM",
                source_type_scope = "PROPERTY,POI",
                auto_patch_count = 2,
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_prompt_autopatch", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_prompt_autopatch_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_prompt_autopatch_count_total")
                .tag("count", "2")
                .counter()
                .count(),
        )
        assertTrue(meterRegistry.get("ai_widget_prompt_autopatch_field_count").summary().count() >= 1)
    }

    @Test
    fun `ingest should accept prompt submit telemetry with submit method`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_prompt_submit",
                source = "text_input",
                route = "LLM",
                source_type_scope = "PROPERTY,PACKAGE",
                submit_method = "keyboard_enter",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_prompt_submit", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_prompt_submit_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_prompt_submit_method_total")
                .tag("method", "keyboard_enter")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_prompt_submit_scope_total")
                .tag("method", "keyboard_enter")
                .tag("scope", "PROPERTY+PACKAGE")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept context insert telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_context_insert_click",
                source = "text_input",
                route = "LLM",
                source_type_scope = "PROPERTY,POI",
                context_field = "city",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_context_insert_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_context_insert_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_context_insert_field_total")
                .tag("field", "city")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_context_insert_scope_total")
                .tag("field", "city")
                .tag("scope", "PROPERTY+POI")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should reject context insert without context field`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_context_insert_click",
                    source = "text_input",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid context insert field`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_context_insert_click",
                    source = "text_input",
                    context_field = "date_range",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should accept context sync telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_context_sync_click",
                source = "context_sync",
                route = "LLM",
                source_type_scope = "PROPERTY,PACKAGE",
                sync_mode = "rerun_last_prompt",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_context_sync_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_context_sync_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_context_sync_mode_total")
                .tag("mode", "rerun_last_prompt")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_context_sync_scope_total")
                .tag("mode", "rerun_last_prompt")
                .tag("scope", "PROPERTY+PACKAGE")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should reject context sync without sync mode`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_context_sync_click",
                    source = "context_sync",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid context sync mode`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_context_sync_click",
                    source = "context_sync",
                    sync_mode = "auto_refresh",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should accept search blocked telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_search_blocked",
                source = "search_cta",
                route = "CLARIFY",
                source_type_scope = "PROPERTY,POI",
                clarify_required = false,
                missing_slot_count = 0,
                block_reason = "context_drift",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_search_blocked", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_search_blocked_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_search_block_reason_total")
                .tag("reason", "context_drift")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_search_block_scope_total")
                .tag("reason", "context_drift")
                .tag("scope", "PROPERTY+POI")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should reject search blocked without block reason`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_search_blocked",
                    source = "search_cta",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid search blocked reason`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_search_blocked",
                    source = "search_cta",
                    block_reason = "unknown",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should accept prompt reuse telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_prompt_reuse_click",
                source = "prompt_history",
                route = "LLM",
                source_type_scope = "PROPERTY",
                reuse_rank = 1,
                reuse_action = "submit",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_prompt_reuse_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_prompt_reuse_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_prompt_reuse_rank_total")
                .tag("rank", "1")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_prompt_reuse_action_total")
                .tag("action", "submit")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_prompt_reuse_scope_total")
                .tag("scope", "PROPERTY")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept error recovery telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_error_recovery_click",
                source = "error_panel",
                route = "LLM",
                source_type_scope = "PROPERTY,POI",
                recovery_action = "retry",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_error_recovery_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_error_recovery_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_error_recovery_action_total")
                .tag("action", "retry")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_error_recovery_scope_total")
                .tag("action", "retry")
                .tag("scope", "PROPERTY+POI")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept card type filter telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_card_type_filter_click",
                source = "results_cta",
                route = "LLM",
                source_type_scope = "PROPERTY,PACKAGE",
                target_source_type = "PACKAGE",
                visible_card_count = 3,
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_card_type_filter_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_card_type_filter_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_type_filter_target_total")
                .tag("target", "PACKAGE")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_type_filter_scope_total")
                .tag("scope", "PROPERTY+PACKAGE")
                .counter()
                .count(),
        )
        assertTrue(meterRegistry.get("ai_widget_card_type_visible_count").summary().count() >= 1)
    }

    @Test
    fun `ingest should accept filter bulk apply telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_filter_bulk_apply",
                source = "filter_bulk",
                route = "LLM",
                source_type_scope = "PROPERTY",
                filter_count = 6,
                bulk_action = "select_all",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_filter_bulk_apply", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_filter_bulk_apply_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_filter_bulk_action_total")
                .tag("action", "select_all")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept card list toggle telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_card_list_toggle_click",
                source = "results_cta",
                route = "LLM",
                source_type_scope = "PROPERTY",
                card_list_state = "expanded",
                visible_card_count = 6,
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_card_list_toggle_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_card_list_toggle_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_list_state_total")
                .tag("state", "expanded")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_list_scope_total")
                .tag("scope", "PROPERTY")
                .counter()
                .count(),
        )
        assertTrue(meterRegistry.get("ai_widget_card_list_visible_count").summary().count() >= 1)
    }

    @Test
    fun `ingest should accept card save telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_card_save_click",
                source = "results_cta",
                route = "LLM",
                source_type_scope = "PROPERTY,POI",
                target_source_type = "PROPERTY",
                card_save_state = "saved",
                saved_card_count = 4,
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_card_save_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_card_save_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_save_state_total")
                .tag("state", "saved")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_save_source_type_total")
                .tag("source_type", "PROPERTY")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_save_scope_total")
                .tag("scope", "PROPERTY+POI")
                .counter()
                .count(),
        )
        assertTrue(meterRegistry.get("ai_widget_card_save_count").summary().count() >= 1)
    }

    @Test
    fun `ingest should accept card followup telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_card_followup_click",
                source = "saved_card",
                route = "LLM",
                source_type_scope = "PROPERTY,POI",
                target_source_type = "PROPERTY",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_card_followup_click", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_card_followup_click_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_followup_source_type_total")
                .tag("source_type", "PROPERTY")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_followup_scope_total")
                .tag("scope", "PROPERTY+POI")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_card_followup_origin_total")
                .tag("origin", "saved_card")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept generation cancel telemetry event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_generation_cancel",
                source = "results_cta",
                route = "LLM",
                source_type_scope = "PROPERTY,POI",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_generation_cancel", response.data.event_name)
        assertEquals(1.0, meterRegistry.get("ai_widget_generation_cancel_total").counter().count())
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_generation_cancel_scope_total")
                .tag("scope", "PROPERTY+POI")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should record answer feedback metric`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_answer_feedback",
                source = "feedback",
                route = "LLM",
                feedback_value = "positive",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals("ai_widget_answer_feedback", response.data.event_name)
        assertEquals(
            1.0,
            meterRegistry.get("ai_widget_answer_feedback_total")
                .tag("feedback", "positive")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should reject invalid feedback value`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_answer_feedback",
                    source = "feedback",
                    feedback_value = "neutral",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid bulk action`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_filter_bulk_apply",
                    source = "filter_bulk",
                    bulk_action = "toggle",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid source type scope`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_prompt_submit",
                    source = "text_input",
                    source_type_scope = "PROPERTY,INVALID",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid prompt submit method`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_prompt_submit",
                    source = "text_input",
                    submit_method = "enter",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject error recovery without action`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_error_recovery_click",
                    source = "error_panel",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid error recovery action`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_error_recovery_click",
                    source = "error_panel",
                    recovery_action = "retry_now",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid missing slot count`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_search_handoff",
                    source = "search_cta",
                    missing_slot_count = 9,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid clarify click count`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_search_handoff",
                    source = "search_cta",
                    clarify_click_count = 99,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid clarify slot`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_clarify_action_click",
                    source = "handoff_clarify",
                    clarify_slot = "invalid_slot",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject quick fix without clarify slot`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_quick_fix_click",
                    source = "quick_fix",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject prompt autopatch without auto patch count`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_prompt_autopatch",
                    source = "text_input",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid auto patch count`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_prompt_autopatch",
                    source = "text_input",
                    auto_patch_count = 5,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid prompt reuse rank`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_prompt_reuse_click",
                    source = "prompt_history",
                    reuse_rank = 7,
                    reuse_action = "draft",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject prompt reuse without action`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_prompt_reuse_click",
                    source = "prompt_history",
                    reuse_rank = 1,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid prompt reuse action`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_prompt_reuse_click",
                    source = "prompt_history",
                    reuse_rank = 1,
                    reuse_action = "rerun",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject card type filter without target source type`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_card_type_filter_click",
                    source = "results_cta",
                    visible_card_count = 2,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid card type filter target`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_card_type_filter_click",
                    source = "results_cta",
                    target_source_type = "UNKNOWN",
                    visible_card_count = 2,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid card type filter visible card count`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_card_type_filter_click",
                    source = "results_cta",
                    target_source_type = "PROPERTY",
                    visible_card_count = 22,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject card list toggle without state`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_card_list_toggle_click",
                    source = "results_cta",
                    visible_card_count = 5,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid card list toggle state`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_card_list_toggle_click",
                    source = "results_cta",
                    card_list_state = "opened",
                    visible_card_count = 5,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject card save without state`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_card_save_click",
                    source = "results_cta",
                    target_source_type = "PROPERTY",
                    saved_card_count = 1,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject card save with all target source type`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_card_save_click",
                    source = "results_cta",
                    target_source_type = "ALL",
                    card_save_state = "saved",
                    saved_card_count = 1,
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject card followup without target source type`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_card_followup_click",
                    source = "results_card",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject card followup with all target source type`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_card_followup_click",
                    source = "results_card",
                    target_source_type = "ALL",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should reject invalid sort value`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_sort_hint_click",
                    source = "filter_chip",
                    sort_value = "invalid_sort",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should accept action apply event and record funnel success`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_action_apply",
                source = "results_cta",
                route = "LLM",
                source_type_scope = "PROPERTY",
                action_apply_success = true,
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals(
            1.0,
            meterRegistry.get("ai_copilot_funnel_step_total")
                .tag("step", "action_apply")
                .tag("source", "results_cta")
                .tag("route", "llm")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("ai_copilot_action_apply_total")
                .tag("result", "success")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should reject action apply without success flag`() {
        val exception = assertThrows<DomainException> {
            controller.ingest(
                TelemetryEventRequest(
                    event_name = "ai_widget_action_apply",
                    source = "results_cta",
                ),
            )
        }

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
    }

    @Test
    fun `ingest should accept booking confirm funnel event`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_booking_confirm",
                source = "results_cta",
                route = "LLM",
                source_type_scope = "PROPERTY",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals(
            1.0,
            meterRegistry.get("ai_copilot_funnel_step_total")
                .tag("step", "booking_confirm")
                .tag("source", "results_cta")
                .tag("route", "llm")
                .counter()
                .count(),
        )
    }

    @Test
    fun `ingest should accept orchestrator fallback event and record quality metric`() {
        val response = controller.ingest(
            TelemetryEventRequest(
                event_name = "ai_widget_orchestrator_fallback",
                source = "error_panel",
                route = "LLM",
                source_type_scope = "PROPERTY",
            ),
        )

        assertTrue(response.data.accepted)
        assertEquals(
            1.0,
            meterRegistry.get("ai_copilot_quality_event_total")
                .tag("metric", "fallback")
                .counter()
                .count(),
        )
    }
}
