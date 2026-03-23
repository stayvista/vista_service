package com.devoceanblue.stayvista.domain.support

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Service

@Service
class CustomerInquiryService(
    private val mapper: CustomerInquiryMapper,
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
        val items = mapper.listInquiries(userId = userId, limit = safeLimit)
            .map { row ->
                CustomerInquirySummary(
                    inquiry_id = row.id,
                    inquiry_type = row.inquiryType,
                    title = row.title,
                    status = row.status,
                    status_label = STATUS_LABELS[row.status] ?: row.status,
                    created_at = row.createdAt?.toInstant()?.toString() ?: Instant.EPOCH.toString(),
                    answered_at = row.answeredAt?.toInstant()?.toString(),
                )
            }

        meterRegistry.counter("customer_inquiry_list_total").increment()
        return CustomerInquiryListData(items = items)
    }

    fun getInquiry(userId: Long, inquiryId: Long): CustomerInquiryDetailData {
        domainSupportService.ensureUserExists(userId)
        val inquiry = mapper.findInquiry(
            inquiryId = inquiryId,
            userId = userId,
        )?.let { row ->
            CustomerInquiryDetailData(
                inquiry_id = row.id,
                inquiry_type = row.inquiryType,
                title = row.title,
                content = row.content,
                status = row.status,
                status_label = STATUS_LABELS[row.status] ?: row.status,
                answer_content = row.answerContent,
                created_at = row.createdAt?.toInstant()?.toString() ?: Instant.EPOCH.toString(),
                updated_at = row.updatedAt?.toInstant()?.toString() ?: Instant.EPOCH.toString(),
                answered_at = row.answeredAt?.toInstant()?.toString(),
            )
        } ?: run {
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

        val command = CreateCustomerInquiryCommand(
            userId = userId,
            inquiryType = inquiryType,
            title = title,
            content = content,
        )
        mapper.insertInquiry(command)

        val createdId = command.id ?: throw DomainException(
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

data class CustomerInquirySummaryRow(
    val id: Long,
    val inquiryType: String,
    val title: String,
    val status: String,
    val createdAt: java.sql.Timestamp?,
    val answeredAt: java.sql.Timestamp?,
)

data class CustomerInquiryDetailRow(
    val id: Long,
    val inquiryType: String,
    val title: String,
    val content: String,
    val status: String,
    val answerContent: String?,
    val createdAt: java.sql.Timestamp?,
    val updatedAt: java.sql.Timestamp?,
    val answeredAt: java.sql.Timestamp?,
)

data class CreateCustomerInquiryCommand(
    val userId: Long,
    val inquiryType: String,
    val title: String,
    val content: String,
    var id: Long? = null,
)

@Mapper
interface CustomerInquiryMapper {
    @Select(
        """
        SELECT id,
               inquiry_type AS inquiryType,
               title,
               status,
               created_at AS createdAt,
               answered_at AS answeredAt
        FROM customer_inquiry
        WHERE user_id = #{userId}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """,
    )
    fun listInquiries(
        @Param("userId") userId: Long,
        @Param("limit") limit: Int,
    ): List<CustomerInquirySummaryRow>

    @Select(
        """
        SELECT id,
               inquiry_type AS inquiryType,
               title,
               content,
               status,
               answer_content AS answerContent,
               created_at AS createdAt,
               updated_at AS updatedAt,
               answered_at AS answeredAt
        FROM customer_inquiry
        WHERE id = #{inquiryId}
          AND user_id = #{userId}
        LIMIT 1
        """,
    )
    fun findInquiry(
        @Param("inquiryId") inquiryId: Long,
        @Param("userId") userId: Long,
    ): CustomerInquiryDetailRow?

    @Insert(
        """
        INSERT INTO customer_inquiry(user_id, inquiry_type, title, content, status)
        VALUES (#{userId}, #{inquiryType}, #{title}, #{content}, 'RECEIVED')
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertInquiry(command: CreateCustomerInquiryCommand): Int
}
