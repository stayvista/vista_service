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
}
