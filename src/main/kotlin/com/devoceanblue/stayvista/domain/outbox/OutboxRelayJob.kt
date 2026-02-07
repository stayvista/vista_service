package com.devoceanblue.stayvista.domain.outbox

import com.devoceanblue.stayvista.domain.search.SearchIndexSyncService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxRelayJob(
    private val jdbcTemplate: JdbcTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val searchIndexSyncService: SearchIndexSyncService,
    private val meterRegistry: MeterRegistry,
) {
    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    fun relay() {
        val rows = jdbcTemplate.query(
            """
            SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload_json
            FROM outbox_event
            WHERE status = 'NEW'
            ORDER BY id
            LIMIT 100
            """.trimIndent(),
            { rs, _ ->
                OutboxRow(
                    id = rs.getLong("id"),
                    eventId = rs.getString("event_id"),
                    aggregateType = rs.getString("aggregate_type"),
                    aggregateId = rs.getString("aggregate_id"),
                    eventType = rs.getString("event_type"),
                    payload = rs.getString("payload_json"),
                )
            },
        )

        rows.forEach { row ->
            try {
                searchIndexSyncService.syncCatalogEvent(
                    aggregateType = row.aggregateType,
                    aggregateId = row.aggregateId,
                    eventType = row.eventType,
                )

                kafkaTemplate.send("stayvista.events", row.eventType, row.payload).get()
                jdbcTemplate.update(
                    """
                    UPDATE outbox_event
                    SET status='PUBLISHED', published_at=NOW(3)
                    WHERE id=?
                    """.trimIndent(),
                    row.id,
                )
                meterRegistry.counter("outbox_published_total").increment()
            } catch (_: Exception) {
                jdbcTemplate.update(
                    """
                    UPDATE outbox_event
                    SET status='FAILED'
                    WHERE id=?
                    """.trimIndent(),
                    row.id,
                )
                meterRegistry.counter("outbox_failed_total").increment()
                meterRegistry.counter("search_index_upsert_total", "result", "fail").increment()
            }
        }
    }
}

private data class OutboxRow(
    val id: Long,
    val eventId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
)
