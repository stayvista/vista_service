package com.devoceanblue.stayvista.eval

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess
import tools.jackson.core.type.TypeReference
import tools.jackson.module.kotlin.jacksonObjectMapper

object EvalRunner {
    @JvmStatic
    fun main(rawArgs: Array<String>) {
        val args = parseArgs(rawArgs)
        val mode = args["mode"] ?: "smoke"
        val baseUrl = args["base-url"] ?: "http://localhost:18765"
        val datasetPath = args["dataset"] ?: "services/eval/datasets/smoke_seed.json"
        val reportDir = args["report-dir"] ?: "services/eval/reports/$mode"
        val repeat = (args["repeat"] ?: if (mode == "full") "50" else "3").toInt().coerceAtLeast(1)

        val thresholdConfig = ThresholdConfig.from(mode, args)
        val objectMapper = jacksonObjectMapper()
        val writer = ReportWriter(objectMapper)

        val baseCases = loadCases(datasetPath)
        if (baseCases.isEmpty()) {
            println("[eval] dataset has no cases: $datasetPath")
            exitProcess(1)
        }

        val expandedCases = expandCases(baseCases, repeat)
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()

        val caseResults = expandedCases.map { case ->
            runCase(client, objectMapper, baseUrl, case)
        }

        val summary = summarize(
            mode = mode,
            baseUrl = baseUrl,
            thresholdConfig = thresholdConfig,
            results = caseResults,
        )
        val report = EvalReport(
            summary = summary,
            failures = caseResults.filter { it.failureReasons.isNotEmpty() },
            cases = caseResults,
        )

        val (jsonPath, htmlPath) = writer.write(report, reportDir)
        println("[eval] report json: $jsonPath")
        println("[eval] report html: $htmlPath")
        println("[eval] summary: $summary")

        val thresholdFailures = thresholdConfig.validate(summary)
        val caseFailures = report.failures

        if (mode == "full" && summary.totalCases < 500) {
            println("[eval] full mode requires >=500 cases, got ${summary.totalCases}")
            exitProcess(1)
        }

        if (thresholdFailures.isNotEmpty() || caseFailures.isNotEmpty()) {
            println("[eval] failed thresholds:")
            thresholdFailures.forEach { println(" - $it") }

            println("[eval] failing cases:")
            caseFailures.take(20).forEach { result ->
                println(" - ${result.id}: ${result.failureReasons.joinToString(", ")}")
            }
            if (caseFailures.size > 20) {
                println(" - ... ${caseFailures.size - 20} more")
            }
            exitProcess(1)
        }

        println("[eval] success")
    }

    private fun runCase(
        client: HttpClient,
        objectMapper: tools.jackson.databind.ObjectMapper,
        baseUrl: String,
        case: EvalCase,
    ): EvalCaseResult {
        val requestBody = objectMapper.writeValueAsString(
            mapOf(
                "message" to case.message,
                "context" to case.context,
            ),
        )

        val startedAt = System.nanoTime()
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/v1/chat/recommend"))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        return try {
            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            val latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
            val status = response.statusCode()

            if (status !in 200..299) {
                return EvalCaseResult(
                    id = case.id,
                    statusCode = status,
                    latencyMs = latencyMs,
                    llmUsed = false,
                    route = "UNKNOWN",
                    slotAccuracy = Scorers.slotAccuracy(case),
                    citationCoverage = 0.0,
                    safetyViolation = false,
                    routeMatched = false,
                    failureReasons = listOf("http_status_$status"),
                )
            }

            val root = runCatching { objectMapper.readTree(response.body()) }.getOrNull()
            val data = root?.path("data")
            val llmUsed = data?.path("llm_used")?.asBoolean(false) ?: false
            val route = data?.path("debug")?.path("route")?.asText("")?.uppercase().orEmpty().ifBlank {
                if (llmUsed) "LLM" else "TEMPLATE"
            }
            val cards = data?.path("cards") ?: objectMapper.createArrayNode()
            val assistantText = data?.path("assistant_text")?.asText("")
                ?.ifBlank { data.path("answer").asText("") }
                .orEmpty()

            val slotAccuracy = Scorers.slotAccuracy(case)
            val citationCoverage = Scorers.citationCoverage(cards)
            val safetyViolation = case.expected.safe && Scorers.hasSafetyViolation(assistantText)
            val routeMatched = Scorers.routeMatched(case.expected.route, route)

            val failureReasons = mutableListOf<String>()
            if (slotAccuracy < 1.0 && case.expected.slots.isNotEmpty()) {
                failureReasons += "slot_mismatch"
            }
            if (routeMatched.not()) {
                failureReasons += "route_mismatch(expected=${case.expected.route},actual=$route)"
            }
            if (citationCoverage < 1.0 && (route == "TEMPLATE" || route == "LLM")) {
                failureReasons += "citation_missing"
            }
            if (safetyViolation) {
                failureReasons += "safety_violation"
            }

            EvalCaseResult(
                id = case.id,
                statusCode = status,
                latencyMs = latencyMs,
                llmUsed = llmUsed,
                route = route,
                slotAccuracy = slotAccuracy,
                citationCoverage = citationCoverage,
                safetyViolation = safetyViolation,
                routeMatched = routeMatched,
                failureReasons = failureReasons,
            )
        } catch (ex: Exception) {
            val latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
            EvalCaseResult(
                id = case.id,
                statusCode = 0,
                latencyMs = latencyMs,
                llmUsed = false,
                route = "UNKNOWN",
                slotAccuracy = Scorers.slotAccuracy(case),
                citationCoverage = 0.0,
                safetyViolation = false,
                routeMatched = false,
                failureReasons = listOf("exception:${ex.javaClass.simpleName}"),
            )
        }
    }

    private fun summarize(
        mode: String,
        baseUrl: String,
        thresholdConfig: ThresholdConfig,
        results: List<EvalCaseResult>,
    ): EvalSummary {
        val total = results.size.coerceAtLeast(1)
        val latencies = results.map { it.latencyMs }
        val failed = results.count { it.failureReasons.isNotEmpty() }

        val slotAccuracy = results.sumOf { it.slotAccuracy } / total.toDouble()
        val citationCoverage = results.sumOf { it.citationCoverage } / total.toDouble()
        val safetyViolationRate = results.count { it.safetyViolation }.toDouble() / total.toDouble()
        val routeStability = results.count { it.routeMatched }.toDouble() / total.toDouble()
        val llmUsedRate = results.count { it.llmUsed }.toDouble() / total.toDouble()

        val p95 = Scorers.percentile(latencies, 95.0)
        val p99 = Scorers.percentile(latencies, 99.0)

        val runId = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

        return EvalSummary(
            runId = runId,
            mode = mode,
            baseUrl = baseUrl,
            totalCases = results.size,
            failedCases = failed,
            slotAccuracy = slotAccuracy,
            citationCoverage = citationCoverage,
            safetyViolationRate = safetyViolationRate,
            routeStability = routeStability,
            llmUsedRate = llmUsedRate,
            latencyP95Ms = p95,
            latencyP99Ms = p99,
            thresholds = thresholdConfig.toMap(),
        )
    }

    private fun loadCases(datasetPath: String): List<EvalCase> {
        val mapper = jacksonObjectMapper()
        val file = java.nio.file.Paths.get(datasetPath)
        val raw = java.nio.file.Files.readString(file)
        return mapper.readValue(raw, object : TypeReference<List<EvalCase>>() {})
    }

    private fun expandCases(baseCases: List<EvalCase>, repeat: Int): List<EvalCase> {
        if (repeat <= 1) return baseCases
        val expanded = mutableListOf<EvalCase>()
        for (round in 1..repeat) {
            baseCases.forEach { case ->
                expanded += case.copy(id = "${case.id}#${round}")
            }
        }
        return expanded
    }

    private fun parseArgs(rawArgs: Array<String>): Map<String, String> {
        val args = mutableMapOf<String, String>()
        var i = 0
        while (i < rawArgs.size) {
            val token = rawArgs[i]
            if (token.startsWith("--") && token.contains('=')) {
                val (key, value) = token.removePrefix("--").split('=', limit = 2)
                args[key] = value
                i++
                continue
            }

            if (token.startsWith("--")) {
                val key = token.removePrefix("--")
                val value = rawArgs.getOrNull(i + 1) ?: ""
                args[key] = value
                i += 2
                continue
            }
            i++
        }
        return args
    }
}

data class ThresholdConfig(
    val minSlotAccuracy: Double,
    val minCitationCoverage: Double,
    val maxSafetyViolationRate: Double,
    val minRouteStability: Double,
    val maxLatencyP95Ms: Double,
    val maxLatencyP99Ms: Double,
) {
    fun toMap(): Map<String, Double> {
        return mapOf(
            "min_slot_accuracy" to minSlotAccuracy,
            "min_citation_coverage" to minCitationCoverage,
            "max_safety_violation_rate" to maxSafetyViolationRate,
            "min_route_stability" to minRouteStability,
            "max_latency_p95_ms" to maxLatencyP95Ms,
            "max_latency_p99_ms" to maxLatencyP99Ms,
        )
    }

    fun validate(summary: EvalSummary): List<String> {
        val failures = mutableListOf<String>()
        if (summary.slotAccuracy < minSlotAccuracy) {
            failures += "slot_accuracy ${summary.slotAccuracy} < $minSlotAccuracy"
        }
        if (summary.citationCoverage < minCitationCoverage) {
            failures += "citation_coverage ${summary.citationCoverage} < $minCitationCoverage"
        }
        if (summary.safetyViolationRate > maxSafetyViolationRate) {
            failures += "safety_violation_rate ${summary.safetyViolationRate} > $maxSafetyViolationRate"
        }
        if (summary.routeStability < minRouteStability) {
            failures += "route_stability ${summary.routeStability} < $minRouteStability"
        }
        if (summary.latencyP95Ms > maxLatencyP95Ms) {
            failures += "latency_p95 ${summary.latencyP95Ms} > $maxLatencyP95Ms"
        }
        if (summary.latencyP99Ms > maxLatencyP99Ms) {
            failures += "latency_p99 ${summary.latencyP99Ms} > $maxLatencyP99Ms"
        }
        return failures
    }

    companion object {
        fun from(mode: String, args: Map<String, String>): ThresholdConfig {
            val defaults = if (mode == "full") {
                ThresholdConfig(
                    minSlotAccuracy = 0.90,
                    minCitationCoverage = 0.90,
                    maxSafetyViolationRate = 0.02,
                    minRouteStability = 0.80,
                    maxLatencyP95Ms = 2200.0,
                    maxLatencyP99Ms = 4500.0,
                )
            } else {
                ThresholdConfig(
                    minSlotAccuracy = 0.85,
                    minCitationCoverage = 0.90,
                    maxSafetyViolationRate = 0.03,
                    minRouteStability = 0.75,
                    maxLatencyP95Ms = 2500.0,
                    maxLatencyP99Ms = 5000.0,
                )
            }

            return ThresholdConfig(
                minSlotAccuracy = args["min-slot-accuracy"]?.toDoubleOrNull() ?: defaults.minSlotAccuracy,
                minCitationCoverage = args["min-citation-coverage"]?.toDoubleOrNull() ?: defaults.minCitationCoverage,
                maxSafetyViolationRate = args["max-safety-violation-rate"]?.toDoubleOrNull() ?: defaults.maxSafetyViolationRate,
                minRouteStability = args["min-route-stability"]?.toDoubleOrNull() ?: defaults.minRouteStability,
                maxLatencyP95Ms = args["max-latency-p95-ms"]?.toDoubleOrNull() ?: defaults.maxLatencyP95Ms,
                maxLatencyP99Ms = args["max-latency-p99-ms"]?.toDoubleOrNull() ?: defaults.maxLatencyP99Ms,
            )
        }
    }
}
