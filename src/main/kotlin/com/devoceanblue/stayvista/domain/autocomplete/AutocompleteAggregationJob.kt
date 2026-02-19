package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.domain.common.PlaceType
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class AutocompleteAggregationJob(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val openSearchGateway: AutocompleteOpenSearchGateway,
    private val cacheService: AutocompleteCacheService,
    private val meterRegistry: MeterRegistry,
    @Value("\${stayvista.autocomplete.aggregate.enabled:true}") private val aggregationEnabled: Boolean,
    @Value("\${stayvista.autocomplete.aggregate.lookback-hours:168}") private val lookbackHours: Long,
    @Value("\${stayvista.autocomplete.aggregate.scan-limit:20000}") private val scanLimit: Int,
) {
    @Scheduled(
        fixedDelayString = "\${stayvista.autocomplete.aggregate.fixed-delay-ms:600000}",
        initialDelayString = "\${stayvista.autocomplete.aggregate.initial-delay-ms:45000}",
    )
    fun aggregate() {
        if (!aggregationEnabled) {
            return
        }

        runCatching {
            val aggregated = aggregateRecentEvents()
            upsertMetrics(aggregated)

            val updated = runCatching { openSearchGateway.updateMetrics(aggregated) }
                .getOrElse { 0 }

            if (aggregated.isNotEmpty()) {
                cacheService.invalidateAutocompleteCaches()
            }

            meterRegistry.counter("ac_aggregate_rows_total").increment(aggregated.size.toDouble())
            meterRegistry.counter("ac_aggregate_runs_total", "result", "success").increment()
            meterRegistry.counter("ac_aggregate_os_update_total", "result", "success").increment(updated.toDouble())
        }.onFailure {
            meterRegistry.counter("ac_aggregate_runs_total", "result", "failed").increment()
        }
    }

    private fun aggregateRecentEvents(): List<AutocompleteMetricRow> {
        val fromInstant = Instant.now().minus(Duration.ofHours(lookbackHours.coerceAtLeast(1)))

        val rows = jdbcTemplate.query(
            """
            SELECT event_type, payload_json
            FROM outbox_event
            WHERE event_type IN ('ac_impression', 'ac_select')
              AND created_at >= ?
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                OutboxPayload(
                    eventType = rs.getString("event_type"),
                    payloadJson = rs.getString("payload_json"),
                )
            },
            Timestamp.from(fromInstant.truncatedTo(ChronoUnit.MILLIS)),
            scanLimit.coerceAtLeast(100),
        )

        val aggregates = linkedMapOf<String, MutableAggregate>()
        rows.forEach { row ->
            val payload = runCatching { objectMapper.readTree(row.payloadJson) }.getOrNull() ?: return@forEach
            when (row.eventType) {
                "ac_impression" -> {
                    payload.path("items").forEach { itemNode ->
                        val typed = parseItemNode(itemNode) ?: return@forEach
                        val key = "${typed.type.name}:${typed.canonicalId.lowercase()}"
                        val aggregate = aggregates.getOrPut(key) { MutableAggregate(type = typed.type, canonicalId = typed.canonicalId) }
                        aggregate.impressions += 1
                    }
                }

                "ac_select" -> {
                    val selected = parseItemNode(payload.path("selected"))
                    if (selected != null) {
                        val key = "${selected.type.name}:${selected.canonicalId.lowercase()}"
                        val aggregate = aggregates.getOrPut(key) {
                            MutableAggregate(type = selected.type, canonicalId = selected.canonicalId)
                        }
                        aggregate.selects += 1
                    }
                }
            }
        }

        return aggregates.values
            .map {
                AutocompleteMetricRow(
                    type = it.type,
                    canonicalId = it.canonicalId,
                    impressions7d = it.impressions,
                    selects7d = it.selects,
                )
            }
    }

    private fun upsertMetrics(rows: List<AutocompleteMetricRow>) {
        rows.forEach { row ->
            jdbcTemplate.update(
                """
                INSERT INTO ac_suggest_metric(
                    type,
                    canonical_id,
                    impressions_7d,
                    selects_7d,
                    ctr_7d,
                    popularity_7d,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NOW(3))
                ON DUPLICATE KEY UPDATE
                    impressions_7d = VALUES(impressions_7d),
                    selects_7d = VALUES(selects_7d),
                    ctr_7d = VALUES(ctr_7d),
                    popularity_7d = VALUES(popularity_7d),
                    updated_at = VALUES(updated_at)
                """.trimIndent(),
                row.type.name,
                row.canonicalId,
                row.impressions7d,
                row.selects7d,
                row.ctr7d,
                row.popularity7d,
            )
        }
    }

    private fun parseItemNode(node: JsonNode): TypedItem? {
        if (!node.isObject) return null
        val rawType = node.path("type").asText().trim().uppercase()
        val canonicalId = node.path("canonical_id").asText().trim()
        if (rawType.isBlank() || canonicalId.isBlank()) return null

        val type = runCatching { PlaceType.valueOf(rawType) }.getOrNull() ?: return null
        return TypedItem(type = type, canonicalId = canonicalId)
    }

    private data class OutboxPayload(
        val eventType: String,
        val payloadJson: String,
    )

    private data class TypedItem(
        val type: PlaceType,
        val canonicalId: String,
    )

    private data class MutableAggregate(
        val type: PlaceType,
        val canonicalId: String,
        var impressions: Long = 0,
        var selects: Long = 0,
    )
}
