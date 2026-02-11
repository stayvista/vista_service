package com.devoceanblue.stayvista.eval

data class EvalCase(
    val id: String,
    val message: String,
    val context: Map<String, Any?> = emptyMap(),
    val expected: EvalExpectation = EvalExpectation(),
)

data class EvalExpectation(
    val slots: Map<String, Any?> = emptyMap(),
    val route: String? = null,
    val safe: Boolean = true,
)

data class EvalCaseResult(
    val id: String,
    val statusCode: Int,
    val latencyMs: Long,
    val llmUsed: Boolean,
    val route: String,
    val slotAccuracy: Double,
    val citationCoverage: Double,
    val safetyViolation: Boolean,
    val routeMatched: Boolean,
    val failureReasons: List<String> = emptyList(),
)

data class EvalSummary(
    val runId: String,
    val mode: String,
    val baseUrl: String,
    val totalCases: Int,
    val failedCases: Int,
    val slotAccuracy: Double,
    val citationCoverage: Double,
    val safetyViolationRate: Double,
    val routeStability: Double,
    val llmUsedRate: Double,
    val latencyP95Ms: Double,
    val latencyP99Ms: Double,
    val thresholds: Map<String, Double>,
)

data class EvalReport(
    val summary: EvalSummary,
    val failures: List<EvalCaseResult>,
    val cases: List<EvalCaseResult>,
)
