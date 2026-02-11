package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmExecutionGateTest {
    @Test
    fun `run should reject when queue wait exceeds timeout`() {
        val registry = SimpleMeterRegistry()
        val gate = LlmExecutionGate(
            meterRegistry = registry,
            maxConcurrency = 1,
            maxQueueWaitMs = 20,
        )

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val first = pool.submit<GateResult<String>> {
            gate.run {
                started.countDown()
                release.await(1, TimeUnit.SECONDS)
                "first"
            }
        }

        assertTrue(started.await(1, TimeUnit.SECONDS))

        val second = pool.submit<GateResult<String>> {
            gate.run { "second" }
        }

        val secondResult = second.get(1, TimeUnit.SECONDS)
        assertTrue(secondResult.rejected)
        assertFalse(secondResult.queueWaitMs < 0)

        release.countDown()
        val firstResult = first.get(1, TimeUnit.SECONDS)
        assertFalse(firstResult.rejected)

        pool.shutdownNow()
    }
}
