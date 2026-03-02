package com.devoceanblue.stayvista.domain.support

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Statement
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service

@Service
class CustomerInquiryService(
    private val jdbcTemplate: JdbcTemplate,
    private val domainSupportService: DomainSupportService,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private val ALLOWED_TYPES = setOf("주문/배송", "결제/환불", "쿠폰/혜택", "예약/변경", "기타")
        private val STATUS_LABELS = mapOf(
            "RECEIVED" to "접수 완료",
            "IN_PROGRESS" to "처리 중",
            "ANSWERED" to "답변 완료",
        )
    }

    fun listInquiries(userId: Long, limit: Int): CustomerInquiryListData {
        domainSupportService.ensureUserExists(userId)
        val safeLimit = limit.coerceIn(1, 100)
        val items = jdbcTemplate.query(
            """
            SELECT id, inquiry_type, title, status, created_at, answered_at
            FROM customer_inquiry
            WHERE user_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                CustomerInquirySummary(
                    inquiry_id = rs.getLong("id"),
                    inquiry_type = rs.getString("inquiry_type"),
                    title = rs.getString("title"),
                    status = rs.getString("status"),
                    status_label = STATUS_LABELS[rs.getString("status")] ?: rs.getString("status"),
                    created_at = rs.getTimestamp("created_at")?.toInstant()?.toString() ?: Instant.EPOCH.toString(),
                    answered_at = rs.getTimestamp("answered_at")?.toInstant()?.toString(),
                )
            },
            userId,
            safeLimit,
        )

        meterRegistry.counter("customer_inquiry_list_total").increment()
        return CustomerInquiryListData(items = items)
    }

    fun getInquiry(userId: Long, inquiryId: Long): CustomerInquiryDetailData {
        domainSupportService.ensureUserExists(userId)
        val inquiry = jdbcTemplate.query(
            """
            SELECT id, inquiry_type, title, content, status, answer_content, created_at, updated_at, answered_at
            FROM customer_inquiry
            WHERE id = ?
              AND user_id = ?
            """.trimIndent(),
            { rs, _ ->
                CustomerInquiryDetailData(
                    inquiry_id = rs.getLong("id"),
                    inquiry_type = rs.getString("inquiry_type"),
                    title = rs.getString("title"),
                    content = rs.getString("content"),
                    status = rs.getString("status"),
                    status_label = STATUS_LABELS[rs.getString("status")] ?: rs.getString("status"),
                    answer_content = rs.getString("answer_content"),
                    created_at = rs.getTimestamp("created_at")?.toInstant()?.toString() ?: Instant.EPOCH.toString(),
                    updated_at = rs.getTimestamp("updated_at")?.toInstant()?.toString() ?: Instant.EPOCH.toString(),
                    answered_at = rs.getTimestamp("answered_at")?.toInstant()?.toString(),
                )
            },
            inquiryId,
            userId,
        ).firstOrNull() ?: run {
            meterRegistry.counter("customer_inquiry_detail_not_found_total").increment()
            throw DomainException(
                errorCode = ErrorCode.NOT_FOUND,
                message = "문의 내역을 찾을 수 없습니다.",
            )
        }

        meterRegistry.counter("customer_inquiry_detail_total").increment()
        return inquiry
    }

    fun createInquiry(userId: Long, request: CustomerInquiryCreateRequest): CustomerInquiryCreateData {
        domainSupportService.ensureUserExists(userId)

        val inquiryType = request.inquiry_type.trim()
        val title = request.title.trim()
        val content = request.content.trim()

        validate(inquiryType, title, content)

        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            connection.prepareStatement(
                """
                INSERT INTO customer_inquiry(user_id, inquiry_type, title, content, status)
                VALUES (?, ?, ?, ?, 'RECEIVED')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).apply {
                setLong(1, userId)
                setString(2, inquiryType)
                setString(3, title)
                setString(4, content)
            }
        }, keyHolder)

        val createdId = keyHolder.key?.toLong() ?: throw DomainException(
            errorCode = ErrorCode.INTERNAL,
            message = "문의 등록에 실패했습니다.",
        )

        meterRegistry.counter("customer_inquiry_create_total").increment()
        return CustomerInquiryCreateData(
            inquiry_id = createdId,
            status = "RECEIVED",
            status_label = STATUS_LABELS["RECEIVED"] ?: "RECEIVED",
        )
    }

    private fun validate(inquiryType: String, title: String, content: String) {
        if (!ALLOWED_TYPES.contains(inquiryType)) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "문의 유형이 올바르지 않습니다.",
                details = mapOf("inquiry_type" to inquiryType),
            )
        }
        if (title.isBlank()) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "문의 제목을 입력해 주세요.",
            )
        }
        if (title.length > 200) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "문의 제목은 200자 이하여야 합니다.",
            )
        }
        if (content.isBlank()) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "문의 내용을 입력해 주세요.",
            )
        }
        if (content.length > 5000) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "문의 내용은 5000자 이하여야 합니다.",
            )
        }
    }
}

data class CustomerInquiryListData(
    val items: List<CustomerInquirySummary>,
)

data class CustomerInquirySummary(
    val inquiry_id: Long,
    val inquiry_type: String,
    val title: String,
    val status: String,
    val status_label: String,
    val created_at: String,
    val answered_at: String?,
)

data class CustomerInquiryDetailData(
    val inquiry_id: Long,
    val inquiry_type: String,
    val title: String,
    val content: String,
    val status: String,
    val status_label: String,
    val answer_content: String?,
    val created_at: String,
    val updated_at: String,
    val answered_at: String?,
)

data class CustomerInquiryCreateRequest(
    val inquiry_type: String,
    val title: String,
    val content: String,
)

data class CustomerInquiryCreateData(
    val inquiry_id: Long,
    val status: String,
    val status_label: String,
)
