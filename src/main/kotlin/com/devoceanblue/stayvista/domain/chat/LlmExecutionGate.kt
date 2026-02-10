package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LlmExecutionGate(
    meterRegistry: MeterRegistry,
    @Value("\${stayvista.chat.llm.max-concurrency:8}") maxConcurrency: Int,
    @Value("\${stayvista.chat.llm.max-queue-wait-ms:350}") private val maxQueueWaitMs: Long,
) {
    private val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1), true)
    private val inflight = AtomicInteger(0)
    private val queueDepth = AtomicInteger(0)
    private val registry = meterRegistry

    init {
        meterRegistry.gauge("llm_inflight", inflight)
        meterRegistry.gauge("llm_queue_depth", queueDepth)
    }

    fun <T> run(block: () -> T): GateResult<T> {
        val queuedAt = System.nanoTime()
        queueDepth.incrementAndGet()

        val acquired = try {
            semaphore.tryAcquire(maxQueueWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        val waitedMs = Duration.ofNanos(System.nanoTime() - queuedAt).toMillis().coerceAtLeast(0)
        queueDepth.decrementAndGet()
        registry.timer("llm_queue_wait_ms").record(Duration.ofMillis(waitedMs))

        if (!acquired) {
            registry.counter("llm_reject_rate", "reason", "queue_timeout").increment()
            return GateResult(
                value = null,
                rejected = true,
                queueWaitMs = waitedMs,
            )
        }

        inflight.incrementAndGet()
        return try {
            GateResult(
                value = block(),
                rejected = false,
                queueWaitMs = waitedMs,
            )
        } finally {
            inflight.decrementAndGet()
            semaphore.release()
        }
    }
}

data class GateResult<T>(
    val value: T?,
    val rejected: Boolean,
    val queueWaitMs: Long,
)
