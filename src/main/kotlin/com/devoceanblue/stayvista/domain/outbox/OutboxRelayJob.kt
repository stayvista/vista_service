package com.devoceanblue.stayvista.domain.outbox

import com.devoceanblue.stayvista.domain.search.SearchIndexSyncService
import io.micrometer.core.instrument.MeterRegistry
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxRelayJob(
    private val mapper: OutboxRelayMapper,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val searchIndexSyncService: SearchIndexSyncService,
    private val meterRegistry: MeterRegistry,
) {
    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    fun relay() {
        val rows = mapper.findNewRows(limit = 100)

        rows.forEach { row ->
            try {
                searchIndexSyncService.syncCatalogEvent(
                    aggregateType = row.aggregateType,
                    aggregateId = row.aggregateId,
                    eventType = row.eventType,
                )

                kafkaTemplate.send("stayvista.events", row.eventType, row.payload).get()
                mapper.markPublished(row.id)
                meterRegistry.counter("outbox_published_total").increment()
            } catch (_: Exception) {
                mapper.markFailed(row.id)
                meterRegistry.counter("outbox_failed_total").increment()
                meterRegistry.counter("search_index_upsert_total", "result", "fail").increment()
            }
        }
    }
}

data class OutboxRow(
    val id: Long,
    val eventId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
)

@Mapper
interface OutboxRelayMapper {
    @Select(
        """
        SELECT id,
               event_id AS eventId,
               aggregate_type AS aggregateType,
               aggregate_id AS aggregateId,
               event_type AS eventType,
               payload_json AS payload
        FROM outbox_event
        WHERE status = 'NEW'
        ORDER BY id
        LIMIT #{limit}
        """,
    )
    fun findNewRows(@Param("limit") limit: Int): List<OutboxRow>

    @Update(
        """
        UPDATE outbox_event
        SET status='PUBLISHED', published_at=NOW(3)
        WHERE id=#{id}
        """,
    )
    fun markPublished(@Param("id") id: Long): Int

    @Update(
        """
        UPDATE outbox_event
        SET status='FAILED'
        WHERE id=#{id}
        """,
    )
    fun markFailed(@Param("id") id: Long): Int
}
