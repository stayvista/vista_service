package com.devoceanblue.stayvista.eval

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import tools.jackson.databind.ObjectMapper

class ReportWriter(
    private val objectMapper: ObjectMapper,
) {
    fun write(report: EvalReport, reportDir: String): Pair<Path, Path> {
        val dir = Paths.get(reportDir)
        Files.createDirectories(dir)

        val stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val jsonPath = dir.resolve("eval_report_${stamp}.json")
        val htmlPath = dir.resolve("eval_report_${stamp}.html")

        Files.writeString(jsonPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        Files.writeString(htmlPath, toHtml(report))
        return jsonPath to htmlPath
    }

    private fun toHtml(report: EvalReport): String {
        val summary = report.summary
        val failuresRows = report.failures.joinToString("\n") { result ->
            """
            <tr>
              <td>${escape(result.id)}</td>
              <td>${result.statusCode}</td>
              <td>${escape(result.route)}</td>
              <td>${"%.2f".format(result.latencyMs.toDouble())}</td>
              <td>${escape(result.failureReasons.joinToString("; "))}</td>
            </tr>
            """.trimIndent()
        }

        val failureTable = if (report.failures.isEmpty()) {
            "<p>No failing cases</p>"
        } else {
            """
            <table>
              <thead>
                <tr><th>Case</th><th>Status</th><th>Route</th><th>Latency(ms)</th><th>Reasons</th></tr>
              </thead>
              <tbody>
                $failuresRows
              </tbody>
            </table>
            """.trimIndent()
        }

        return """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <title>StayVista Eval Report</title>
            <style>
              body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 24px; }
              h1, h2 { margin: 0 0 12px 0; }
              table { border-collapse: collapse; width: 100%; margin-top: 12px; }
              th, td { border: 1px solid #d8d8d8; text-align: left; padding: 8px; font-size: 13px; }
              th { background: #f3f3f3; }
              .grid { display: grid; grid-template-columns: repeat(3, minmax(160px, 1fr)); gap: 8px; max-width: 960px; }
              .card { border: 1px solid #ddd; border-radius: 8px; padding: 10px; }
              .label { font-size: 12px; color: #666; }
              .value { font-size: 18px; font-weight: 600; }
            </style>
          </head>
          <body>
            <h1>StayVista Eval Report</h1>
            <p>runId=${escape(summary.runId)} mode=${escape(summary.mode)} baseUrl=${escape(summary.baseUrl)}</p>

            <div class="grid">
              <div class="card"><div class="label">Total Cases</div><div class="value">${summary.totalCases}</div></div>
              <div class="card"><div class="label">Failed Cases</div><div class="value">${summary.failedCases}</div></div>
              <div class="card"><div class="label">Slot Accuracy</div><div class="value">${"%.3f".format(summary.slotAccuracy)}</div></div>
              <div class="card"><div class="label">Citation Coverage</div><div class="value">${"%.3f".format(summary.citationCoverage)}</div></div>
              <div class="card"><div class="label">Safety Violation Rate</div><div class="value">${"%.3f".format(summary.safetyViolationRate)}</div></div>
              <div class="card"><div class="label">Route Stability</div><div class="value">${"%.3f".format(summary.routeStability)}</div></div>
              <div class="card"><div class="label">LLM Used Rate</div><div class="value">${"%.3f".format(summary.llmUsedRate)}</div></div>
              <div class="card"><div class="label">Latency p95 (ms)</div><div class="value">${"%.1f".format(summary.latencyP95Ms)}</div></div>
              <div class="card"><div class="label">Latency p99 (ms)</div><div class="value">${"%.1f".format(summary.latencyP99Ms)}</div></div>
            </div>

            <h2>Failures</h2>
            $failureTable
          </body>
        </html>
        """.trimIndent()
    }

    private fun escape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
