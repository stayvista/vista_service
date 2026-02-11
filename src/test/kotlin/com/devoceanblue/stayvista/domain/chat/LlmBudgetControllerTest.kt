package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmBudgetControllerTest {
    private val controller = LlmBudgetController(
        meterRegistry = SimpleMeterRegistry(),
        clock = Clock.systemUTC(),
        enabled = true,
        sampleWindowSize = 240,
        p99ProtectMs = 1500,
        p99SevereMs = 2600,
        p99RecoverMs = 700,
        rejectRateThreshold = 0.10,
        rejectRateSevere = 0.22,
        timeoutRateThreshold = 0.05,
        degradeAllowPercent = 40,
        severeAllowPercent = 15,
    )

    @Test
    fun `recordOutcome should degrade mode when p99 is high`() {
        repeat(40) {
            controller.recordOutcome(
                attempted = true,
                queueWaitMs = 1200,
                llmElapsedMs = 1700,
                rejected = it % 8 == 0,
                timeout = false,
            )
        }

        assertNotEquals("NORMAL", controller.modeLabel())
        val denied = (1..30).count { idx -> !controller.allowLlm("message-$idx") }
        assertTrue(denied > 0)
    }

    @Test
    fun `recordOutcome should recover mode when pressure is removed`() {
        repeat(60) {
            controller.recordOutcome(
                attempted = true,
                queueWaitMs = 30,
                llmElapsedMs = 260,
                rejected = false,
                timeout = false,
            )
        }

        assertEquals("NORMAL", controller.modeLabel())
    }
}
