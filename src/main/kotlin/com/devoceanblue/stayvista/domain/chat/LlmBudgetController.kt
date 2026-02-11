package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.math.abs

@Service
class LlmBudgetController(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    @Value("\${stayvista.chat.llm.budget.enabled:true}") private val enabled: Boolean,
    @Value("\${stayvista.chat.llm.budget.sample-window-size:240}") private val sampleWindowSize: Int,
    @Value("\${stayvista.chat.llm.budget.p99-protect-ms:4200}") private val p99ProtectMs: Long,
    @Value("\${stayvista.chat.llm.budget.p99-severe-ms:5200}") private val p99SevereMs: Long,
    @Value("\${stayvista.chat.llm.budget.p99-recover-ms:2600}") private val p99RecoverMs: Long,
    @Value("\${stayvista.chat.llm.budget.reject-rate-threshold:0.08}") private val rejectRateThreshold: Double,
    @Value("\${stayvista.chat.llm.budget.reject-rate-severe:0.16}") private val rejectRateSevere: Double,
    @Value("\${stayvista.chat.llm.budget.timeout-rate-threshold:0.03}") private val timeoutRateThreshold: Double,
    @Value("\${stayvista.chat.llm.budget.degrade-allow-percent:45}") private val degradeAllowPercent: Int,
    @Value("\${stayvista.chat.llm.budget.severe-allow-percent:20}") private val severeAllowPercent: Int,
) {
    private val mode = AtomicInteger(0)
    private val samples = ConcurrentLinkedDeque<BudgetSample>()

    init {
        meterRegistry.gauge("chat_llm_budget_mode", mode)
    }

    fun allowLlm(message: String): Boolean {
        if (!enabled) {
            meterRegistry.counter("chat_llm_budget_decision_total", "mode", "disabled", "allowed", "true").increment()
            return true
        }

        val currentMode = mode.get()
        val allowPercent = when (currentMode) {
            2 -> severeAllowPercent.coerceIn(0, 100)
            1 -> degradeAllowPercent.coerceIn(0, 100)
            else -> 100
        }

        val bucket = abs(message.lowercase().hashCode()) % 100
        val allowed = bucket < allowPercent
        meterRegistry.counter(
            "chat_llm_budget_decision_total",
            "mode",
            modeLabel(currentMode),
            "allowed",
            allowed.toString(),
        ).increment()
        return allowed
    }

    fun recordOutcome(
        attempted: Boolean,
        queueWaitMs: Long,
        llmElapsedMs: Long,
        rejected: Boolean,
        timeout: Boolean,
    ) {
        if (!enabled || !attempted) return

        samples += BudgetSample(
            observedAtMillis = clock.millis(),
            combinedLatencyMs = (queueWaitMs + llmElapsedMs).coerceAtLeast(0),
            rejected = rejected,
            timeout = timeout,
        )

        trimSamples()
        recomputeMode()
    }

    fun modeLabel(): String = modeLabel(mode.get())

    private fun recomputeMode() {
        val snapshot = samples.toList()
        if (snapshot.size < 12) {
            return
        }

        val latencies = snapshot.map { it.combinedLatencyMs }.sorted()
        val p99 = percentile(latencies, 0.99)
        val rejectRate = snapshot.count { it.rejected }.toDouble() / snapshot.size
        val timeoutRate = snapshot.count { it.timeout }.toDouble() / snapshot.size

        meterRegistry.summary("chat_llm_budget_p99_ms").record(p99.toDouble())
        meterRegistry.summary("chat_llm_budget_reject_rate").record(rejectRate)
        meterRegistry.summary("chat_llm_budget_timeout_rate").record(timeoutRate)

        val nextMode = when {
            p99 >= p99SevereMs || rejectRate >= rejectRateSevere || timeoutRate >= timeoutRateThreshold -> 2
            p99 >= p99ProtectMs || rejectRate >= rejectRateThreshold -> 1
            p99 <= p99RecoverMs && rejectRate <= (rejectRateThreshold / 2.0) && timeoutRate <= 0.0 -> 0
            else -> mode.get()
        }

        if (mode.getAndSet(nextMode) != nextMode) {
            meterRegistry.counter("chat_llm_budget_mode_change_total", "mode", modeLabel(nextMode)).increment()
        }
    }

    private fun trimSamples() {
        while (samples.size > sampleWindowSize.coerceIn(20, 1000)) {
            samples.pollFirst()
        }
    }

    private fun percentile(values: List<Long>, ratio: Double): Long {
        if (values.isEmpty()) return 0L
        val idx = ((values.size - 1) * ratio).toInt().coerceIn(0, values.size - 1)
        return values[idx]
    }

    private fun modeLabel(modeValue: Int): String {
        return when (modeValue) {
            2 -> "SEVERE"
            1 -> "DEGRADED"
            else -> "NORMAL"
        }
    }

    private data class BudgetSample(
        val observedAtMillis: Long,
        val combinedLatencyMs: Long,
        val rejected: Boolean,
        val timeout: Boolean,
    )
}
