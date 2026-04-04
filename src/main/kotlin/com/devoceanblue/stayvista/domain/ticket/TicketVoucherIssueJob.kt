package com.devoceanblue.stayvista.domain.ticket

import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class TicketVoucherIssueJob(
    private val mapper: TicketVoucherIssueMapper,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    @Scheduled(fixedDelay = 5000, initialDelay = 12000)
    fun issueRequestedVouchers() {
        val rows = mapper.findPublishedRows(limit = 100)

        rows.forEach { row ->
            val claimed = mapper.claimForConsume(row.id)
            if (claimed != 1) {
                return@forEach
            }

            try {
                processOne(row)
                mapper.markConsumed(row.id)
                meterRegistry.counter("voucher_issue_total", "result", "success").increment()
            } catch (_: Exception) {
                mapper.releaseForRetry(row.id)
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

        val existingCount = mapper.countVouchers(orderId)
        if (existingCount >= quantity) {
            meterRegistry.counter("voucher_issue_total", "result", "skipped").increment()
            return
        }

        for (sequence in (existingCount + 1)..quantity) {
            mapper.insertVoucher(
                orderId = orderId,
                userId = userId,
                eventId = eventId,
                sequenceNo = sequence,
                qrPayload = UUID.randomUUID().toString(),
            )
        }
    }
}

data class VoucherIssueOutboxRow(
    val id: Long,
    val payloadJson: String,
)

@Mapper
interface TicketVoucherIssueMapper {
    @Select(
        """
        SELECT id, payload_json AS payloadJson
        FROM outbox_event
        WHERE event_type = 'VoucherIssueRequested'
          AND status = 'PUBLISHED'
        ORDER BY id
        LIMIT #{limit}
        """,
    )
    fun findPublishedRows(@Param("limit") limit: Int): List<VoucherIssueOutboxRow>

    @Update(
        """
        UPDATE outbox_event
        SET status='CONSUMING'
        WHERE id=#{id}
          AND status='PUBLISHED'
        """,
    )
    fun claimForConsume(@Param("id") id: Long): Int

    @Update(
        """
        UPDATE outbox_event
        SET status='CONSUMED',
            published_at=COALESCE(published_at, NOW(3))
        WHERE id=#{id}
          AND status='CONSUMING'
        """,
    )
    fun markConsumed(@Param("id") id: Long): Int

    @Update(
        """
        UPDATE outbox_event
        SET status='PUBLISHED'
        WHERE id=#{id}
          AND status='CONSUMING'
        """,
    )
    fun releaseForRetry(@Param("id") id: Long): Int

    @Select("SELECT COUNT(*) FROM voucher WHERE order_id = #{orderId}")
    fun countVouchers(@Param("orderId") orderId: Long): Int

    @Insert(
        """
        INSERT INTO voucher(order_id, user_id, event_id, sequence_no, status, qr_payload)
        VALUES (#{orderId}, #{userId}, #{eventId}, #{sequenceNo}, 'ISSUED', #{qrPayload})
        ON DUPLICATE KEY UPDATE id=id
        """,
    )
    fun insertVoucher(
        @Param("orderId") orderId: Long,
        @Param("userId") userId: Long,
        @Param("eventId") eventId: Long,
        @Param("sequenceNo") sequenceNo: Int,
        @Param("qrPayload") qrPayload: String,
    ): Int
}
