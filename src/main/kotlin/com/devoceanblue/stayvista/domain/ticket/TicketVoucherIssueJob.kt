package com.devoceanblue.stayvista.domain.ticket

import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class TicketVoucherIssueJob(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    @Scheduled(fixedDelay = 5000, initialDelay = 12000)
    fun issueRequestedVouchers() {
        val rows = jdbcTemplate.query(
            """
            SELECT id, payload_json
            FROM outbox_event
            WHERE event_type = 'VoucherIssueRequested'
              AND status IN ('PUBLISHED', 'FAILED')
            ORDER BY id
            LIMIT 100
            """.trimIndent(),
            { rs, _ ->
                VoucherIssueOutboxRow(
                    id = rs.getLong("id"),
                    payloadJson = rs.getString("payload_json"),
                )
            },
        )

        rows.forEach { row ->
            try {
                processOne(row)
                jdbcTemplate.update(
                    """
                    UPDATE outbox_event
                    SET status='CONSUMED',
                        published_at=COALESCE(published_at, NOW(3))
                    WHERE id=?
                    """.trimIndent(),
                    row.id,
                )
                meterRegistry.counter("voucher_issue_total", "result", "success").increment()
            } catch (_: Exception) {
                meterRegistry.counter("voucher_issue_total", "result", "failed").increment()
            }
        }
    }

    private fun processOne(row: VoucherIssueOutboxRow) {
        val payload = objectMapper.readTree(row.payloadJson)
        val orderId = payload.path("order_id").asLong()
        val userId = payload.path("user_id").asLong()
        val eventId = payload.path("event_id").asLong()
        val quantity = payload.path("quantity").asInt()
        if (orderId <= 0 || userId <= 0 || eventId <= 0 || quantity <= 0) {
            throw IllegalArgumentException("Invalid voucher issue payload")
        }

        val existingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM voucher WHERE order_id = ?",
            Int::class.java,
            orderId,
        ) ?: 0
        if (existingCount >= quantity) {
            meterRegistry.counter("voucher_issue_total", "result", "skipped").increment()
            return
        }

        for (sequence in (existingCount + 1)..quantity) {
            jdbcTemplate.update(
                """
                INSERT INTO voucher(order_id, user_id, event_id, sequence_no, status, qr_payload)
                VALUES (?, ?, ?, ?, 'ISSUED', ?)
                ON DUPLICATE KEY UPDATE id=id
                """.trimIndent(),
                orderId,
                userId,
                eventId,
                sequence,
                UUID.randomUUID().toString(),
            )
        }
    }
}

private data class VoucherIssueOutboxRow(
    val id: Long,
    val payloadJson: String,
)
