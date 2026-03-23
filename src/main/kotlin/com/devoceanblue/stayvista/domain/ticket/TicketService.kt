package com.devoceanblue.stayvista.domain.ticket

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.db.DbRetryExecutor
import com.devoceanblue.stayvista.common.idempotency.IdempotencyService
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import com.devoceanblue.stayvista.domain.payment.PaymentAuthorizationRequest
import com.devoceanblue.stayvista.domain.payment.PaymentGateway
import io.micrometer.core.instrument.MeterRegistry
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class TicketService(
    private val mapper: TicketMapper,
    private val domainSupportService: DomainSupportService,
    private val idempotencyService: IdempotencyService,
    private val retryExecutor: DbRetryExecutor,
    private val transactionTemplate: TransactionTemplate,
    private val paymentGateway: PaymentGateway,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${stayvista.booking.hold-ttl-minutes:10}") private val holdTtlMinutes: Long,
) {
    fun createProduct(request: CreateTicketProductRequest): Long {
        val partnerId = request.partner_id ?: 1L
        domainSupportService.ensurePartnerExists(partnerId, "TICKET_VENDOR")
        val command = TicketProductInsertCommand(
            partnerId = partnerId,
            productType = request.category,
            name = request.name,
            city = request.city,
            imageUrl = request.image_url,
            status = request.status,
        )
        mapper.insertProduct(command)
        return command.id ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create ticket product")
    }

    fun createEvent(productId: Long, request: CreateTicketEventRequest): Long {
        val productExists = mapper.countProduct(productId)
        if (productExists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Ticket product not found")
        }
        val startAt = LocalDateTime.of(request.event_date, request.start_time)
        val endAt = request.end_time?.let { LocalDateTime.of(request.event_date, it) }
        val command = TicketEventInsertCommand(
            productId = productId,
            startTime = Timestamp.valueOf(startAt),
            endTime = endAt?.let { Timestamp.valueOf(it) },
            status = request.status,
        )
        mapper.insertEvent(command)
        return command.id ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create ticket event")
    }

    fun putInventory(eventId: Long, request: PutTicketInventoryRequest) {
        val eventExists = mapper.countEvent(eventId)
        if (eventExists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Ticket event not found")
        }

        val conflict = mapper.findInventoryConflict(eventId = eventId, total = request.total)
        if (conflict != null) {
            throw DomainException(
                ErrorCode.INVENTORY_TOTAL_BELOW_COMMITTED,
                "Inventory total cannot be lower than hold + sold",
            )
        }
        mapper.upsertInventory(eventId = eventId, total = request.total)
    }

    fun listProducts(): TicketProductListData {
        val rows = mapper.listProducts()
        return TicketProductListData(rows)
    }

    fun getProduct(productId: Long): TicketProductDetail {
        return mapper.findProduct(productId) ?: throw DomainException(ErrorCode.NOT_FOUND, "Ticket product not found")
    }

    fun listEvents(productId: Long?, date: LocalDate?): TicketEventListData {
        val rows = mapper.listEvents(productId = productId, eventDate = date?.let(Date::valueOf))
        return TicketEventListData(rows)
    }

    fun hold(userId: Long, idempotencyKey: String, request: TicketHoldRequest): TicketHoldData {
        return idempotencyService.execute(
            scope = "TICKET_HOLD",
            idempotencyKey = idempotencyKey,
            payload = mapOf("user_id" to userId, "request" to request),
            responseType = TicketHoldData::class.java,
        ) {
            retryExecutor.execute {
                transactionTemplate.execute {
                    holdTx(userId, idempotencyKey, request)
                } ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create ticket hold")
            }
        }
    }

    fun confirm(userId: Long, rawOrderId: String, idempotencyKey: String, request: TicketConfirmRequest): TicketConfirmData {
        return idempotencyService.execute(
            scope = "TICKET_CONFIRM",
            idempotencyKey = idempotencyKey,
            payload = mapOf("user_id" to userId, "order_id" to rawOrderId, "request" to request),
            responseType = TicketConfirmData::class.java,
        ) {
            retryExecutor.execute {
                transactionTemplate.execute {
                    confirmTx(userId, parseOrderId(rawOrderId), request)
                } ?: throw DomainException(ErrorCode.INTERNAL, "Failed to confirm ticket order")
            }
        }
    }

    fun listOrderVouchers(userId: Long, rawOrderId: String): TicketVoucherListData {
        val orderId = parseOrderId(rawOrderId)
        val foundOrder = mapper.countUserOrder(orderId = orderId, userId = userId)
        if (foundOrder == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Ticket order not found")
        }

        val vouchers = mapper.listOrderVouchers(orderId = orderId, userId = userId)

        return TicketVoucherListData(
            order_id = "tord_$orderId",
            items = vouchers,
        )
    }

    fun validateVoucher(request: ValidateVoucherRequest): VoucherValidateData {
        val voucherId = resolveVoucherId(request)
        val voucher = mapper.findVoucher(voucherId) ?: throw DomainException(ErrorCode.NOT_FOUND, "Voucher not found")

        if (voucher.status == "REDEEMED") {
            throw DomainException(ErrorCode.ALREADY_USED, "Voucher already redeemed")
        }
        if (voucher.status == "EXPIRED") {
            throw DomainException(ErrorCode.EXPIRED, "Voucher expired")
        }

        mapper.markVoucherRedeemed(voucher.id)
        return VoucherValidateData(
            voucher_id = "vch_${voucher.id}",
            result = "VALID",
        )
    }

    private fun resolveVoucherId(request: ValidateVoucherRequest): Long {
        if (!request.voucher_id.isNullOrBlank()) {
            return request.voucher_id.removePrefix("vch_").toLongOrNull()
                ?: throw DomainException(ErrorCode.VALIDATION_ERROR, "Invalid voucher_id")
        }
        if (!request.qr_payload.isNullOrBlank()) {
            return mapper.findVoucherIdByQrPayload(request.qr_payload)
                ?: throw DomainException(ErrorCode.NOT_FOUND, "Voucher not found")
        }
        throw DomainException(ErrorCode.VALIDATION_ERROR, "voucher_id or qr_payload is required")
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 45000)
    fun expireTicketHolds() {
        val ids = mapper.listExpiredHoldIds(limit = 200)
        ids.forEach { orderId ->
            transactionTemplate.execute {
                expireOne(orderId)
            }
        }
    }

    private fun holdTx(userId: Long, idempotencyKey: String, request: TicketHoldRequest): TicketHoldData {
        domainSupportService.ensureUserExists(userId)
        val event = mapper.findEvent(request.event_id) ?: throw DomainException(ErrorCode.NOT_FOUND, "Ticket event not found")
        if (event.status != "ACTIVE") {
            throw DomainException(ErrorCode.NOT_FOUND, "Ticket event not available")
        }

        val affected = mapper.increaseInventoryHold(
            eventId = request.event_id,
            quantity = request.quantity,
        )
        if (affected != 1) {
            throw DomainException(ErrorCode.TICKET_SOLD_OUT, "Ticket sold out")
        }

        val expiresAt = Instant.now(clock).plusSeconds(holdTtlMinutes * 60)
        val command = TicketOrderInsertCommand(
            userId = userId,
            eventId = request.event_id,
            qty = request.quantity,
            expiresAt = Timestamp.from(expiresAt),
            currency = request.price.currency,
            totalAmount = request.price.amount_total,
            idempotencyKey = idempotencyKey,
        )
        mapper.insertTicketOrder(command)
        val orderId = command.id ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create order")

        return TicketHoldData(
            order_id = "tord_$orderId",
            status = "HOLD",
            expires_at = expiresAt.toString(),
        )
    }

    private fun confirmTx(userId: Long, orderId: Long, request: TicketConfirmRequest): TicketConfirmData {
        domainSupportService.ensureUserExists(userId)
        val order = mapper.findOrderForUpdate(orderId = orderId, userId = userId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "Ticket order not found")

        if (order.status != "HOLD") {
            throw DomainException(ErrorCode.ORDER_STATE_CONFLICT, "Ticket order cannot be confirmed from ${order.status}")
        }
        if (order.expiresAt != null && order.expiresAt.isBefore(Instant.now(clock))) {
            throw DomainException(ErrorCode.ORDER_EXPIRED, "Ticket order hold expired")
        }
        paymentGateway.authorize(
            PaymentAuthorizationRequest(
                paymentMethod = request.payment_method,
                paymentToken = request.payment_token,
                amount = order.totalAmount,
                currency = order.currency,
                referenceType = "TICKET_ORDER",
                referenceId = order.id.toString(),
            ),
        )

        val moved = mapper.moveInventoryHoldToSold(
            eventId = order.eventId,
            quantity = order.quantity,
        )
        if (moved != 1) {
            meterRegistry.counter("ticket_confirm_inventory_conflict_total").increment()
            throw DomainException(ErrorCode.TICKET_SOLD_OUT, "Ticket sold out during confirm")
        }

        mapper.markOrderConfirmed(order.id)

        domainSupportService.appendOutbox(
            aggregateType = "TICKET_ORDER",
            aggregateId = order.id.toString(),
            eventType = "TicketOrderConfirmed",
            payload = mapOf("order_id" to order.id, "user_id" to userId, "event_id" to order.eventId, "quantity" to order.quantity),
        )
        domainSupportService.appendOutbox(
            aggregateType = "TICKET_ORDER",
            aggregateId = order.id.toString(),
            eventType = "VoucherIssueRequested",
            payload = mapOf("order_id" to order.id, "user_id" to userId, "event_id" to order.eventId, "quantity" to order.quantity),
        )

        val voucherIds = mapper.listVoucherIds(order.id)

        return TicketConfirmData(
            order_id = "tord_${order.id}",
            status = "CONFIRMED",
            voucher_ids = voucherIds,
        )
    }

    private fun expireOne(orderId: Long) {
        val order = mapper.findOrderByIdForUpdate(orderId) ?: return

        if (order.status != "HOLD") return
        if (order.expiresAt == null || order.expiresAt.isAfter(Instant.now(clock))) return

        val restored = mapper.restoreInventoryHold(
            eventId = order.eventId,
            quantity = order.quantity,
        )
        if (restored == 1) {
            mapper.markOrderExpired(order.id)
            meterRegistry.counter("ticket_hold_expired_total").increment()
        }
    }

    private fun parseOrderId(rawOrderId: String): Long {
        return rawOrderId.removePrefix("tord_").toLongOrNull()
            ?: throw DomainException(ErrorCode.VALIDATION_ERROR, "Invalid order_id format")
    }
}

data class CreateTicketProductRequest(
    val partner_id: Long? = null,
    val name: String,
    val category: String,
    val city: String? = null,
    val image_url: String? = null,
    val status: String = "ACTIVE",
)

data class CreateTicketEventRequest(
    val event_date: LocalDate,
    val start_time: LocalTime,
    val end_time: LocalTime? = null,
    val status: String = "ACTIVE",
)

data class PutTicketInventoryRequest(
    val total: Int,
)

data class TicketProductSummary(
    val product_id: Long,
    val name: String,
    val category: String,
    val city: String?,
    val status: String,
    val image_url: String?,
)

data class TicketProductDetail(
    val product_id: Long,
    val name: String,
    val category: String,
    val city: String?,
    val status: String,
    val image_url: String?,
)

data class TicketProductListData(
    val items: List<TicketProductSummary>,
)

data class TicketEventSummary(
    val event_id: Long,
    val product_id: Long,
    val event_date: LocalDate,
    val start_time: LocalTime,
    val end_time: LocalTime?,
    val status: String,
    val total: Int,
    val hold: Int,
    val sold: Int,
)

data class TicketEventListData(
    val items: List<TicketEventSummary>,
)

data class TicketHoldPrice(
    val currency: String,
    val amount_total: Long,
)

data class TicketHoldRequest(
    val event_id: Long,
    val quantity: Int,
    val price: TicketHoldPrice,
)

data class TicketHoldData(
    val order_id: String,
    val status: String,
    val expires_at: String,
)

data class TicketConfirmRequest(
    val payment_method: String,
    val payment_token: String,
)

data class TicketConfirmData(
    val order_id: String,
    val status: String,
    val voucher_ids: List<String>,
)

data class TicketVoucherSummary(
    val voucher_id: String,
    val sequence_no: Int,
    val status: String,
    val qr_payload: String,
    val issued_at: String?,
    val redeemed_at: String?,
)

data class TicketVoucherListData(
    val order_id: String,
    val items: List<TicketVoucherSummary>,
)

data class ValidateVoucherRequest(
    val voucher_id: String? = null,
    val qr_payload: String? = null,
)

data class VoucherValidateData(
    val voucher_id: String,
    val result: String,
)

data class TicketEventRow(
    val id: Long,
    val status: String,
)

data class TicketOrderRow(
    val id: Long,
    val eventId: Long,
    val quantity: Int,
    val status: String,
    val expiresAt: Instant?,
    val totalAmount: Long,
    val currency: String,
)

data class VoucherRow(
    val id: Long,
    val status: String,
)

data class TicketProductInsertCommand(
    val partnerId: Long,
    val productType: String,
    val name: String,
    val city: String?,
    val imageUrl: String?,
    val status: String,
    var id: Long? = null,
)

data class TicketEventInsertCommand(
    val productId: Long,
    val startTime: Timestamp,
    val endTime: Timestamp?,
    val status: String,
    var id: Long? = null,
)

data class TicketOrderInsertCommand(
    val userId: Long,
    val eventId: Long,
    val qty: Int,
    val expiresAt: Timestamp,
    val currency: String,
    val totalAmount: Long,
    val idempotencyKey: String,
    var id: Long? = null,
)

@Mapper
interface TicketMapper {
    @Insert(
        """
        INSERT INTO product(partner_id, product_type, name, city, image_url, status)
        VALUES (#{partnerId}, #{productType}, #{name}, #{city}, #{imageUrl}, #{status})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertProduct(command: TicketProductInsertCommand): Int

    @Select("SELECT COUNT(*) FROM product WHERE id = #{productId}")
    fun countProduct(@Param("productId") productId: Long): Long

    @Insert(
        """
        INSERT INTO ticket_event(product_id, start_time, end_time, status)
        VALUES (#{productId}, #{startTime}, #{endTime}, #{status})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertEvent(command: TicketEventInsertCommand): Int

    @Select("SELECT COUNT(*) FROM ticket_event WHERE id = #{eventId}")
    fun countEvent(@Param("eventId") eventId: Long): Long

    @Select(
        """
        SELECT event_id
        FROM ticket_inventory
        WHERE event_id = #{eventId}
          AND #{total} < (hold + sold)
        LIMIT 1
        """,
    )
    fun findInventoryConflict(
        @Param("eventId") eventId: Long,
        @Param("total") total: Int,
    ): Long?

    @Insert(
        """
        INSERT INTO ticket_inventory(event_id, total, hold, sold)
        VALUES (#{eventId}, #{total}, 0, 0)
        ON DUPLICATE KEY UPDATE total = VALUES(total), updated_at = NOW(3)
        """,
    )
    fun upsertInventory(
        @Param("eventId") eventId: Long,
        @Param("total") total: Int,
    ): Int

    @Select(
        """
        SELECT id AS product_id,
               name,
               product_type AS category,
               city,
               status,
               image_url AS image_url
        FROM product
        WHERE status='ACTIVE'
        ORDER BY id DESC
        """,
    )
    fun listProducts(): List<TicketProductSummary>

    @Select(
        """
        SELECT id AS product_id,
               name,
               product_type AS category,
               city,
               status,
               image_url AS image_url
        FROM product
        WHERE id = #{productId}
        LIMIT 1
        """,
    )
    fun findProduct(@Param("productId") productId: Long): TicketProductDetail?

    @Select(
        """
        <script>
        SELECT te.id AS event_id,
               te.product_id AS product_id,
               DATE(te.start_time) AS event_date,
               TIME(te.start_time) AS start_time,
               TIME(te.end_time) AS end_time,
               te.status,
               COALESCE(ti.total, 0) AS total,
               COALESCE(ti.hold, 0) AS hold,
               COALESCE(ti.sold, 0) AS sold
        FROM ticket_event te
        LEFT JOIN ticket_inventory ti ON ti.event_id = te.id
        <where>
          <if test="productId != null">te.product_id = #{productId}</if>
          <if test="eventDate != null">
            <if test="productId != null">AND</if>
            DATE(te.start_time) = #{eventDate}
          </if>
        </where>
        ORDER BY te.start_time
        </script>
        """,
    )
    fun listEvents(
        @Param("productId") productId: Long?,
        @Param("eventDate") eventDate: Date?,
    ): List<TicketEventSummary>

    @Select(
        """
        SELECT COUNT(*)
        FROM ticket_order
        WHERE id = #{orderId} AND user_id = #{userId}
        """,
    )
    fun countUserOrder(
        @Param("orderId") orderId: Long,
        @Param("userId") userId: Long,
    ): Long

    @Select(
        """
        SELECT CONCAT('vch_', id) AS voucher_id,
               sequence_no,
               status,
               qr_payload,
               issued_at AS issued_at,
               redeemed_at AS redeemed_at
        FROM voucher
        WHERE order_id = #{orderId} AND user_id = #{userId}
        ORDER BY sequence_no
        """,
    )
    fun listOrderVouchers(
        @Param("orderId") orderId: Long,
        @Param("userId") userId: Long,
    ): List<TicketVoucherSummary>

    @Select(
        """
        SELECT id, status
        FROM voucher
        WHERE id = #{voucherId}
        LIMIT 1
        """,
    )
    fun findVoucher(@Param("voucherId") voucherId: Long): VoucherRow?

    @Update(
        """
        UPDATE voucher
        SET status='REDEEMED', redeemed_at=NOW(3)
        WHERE id=#{voucherId} AND status='ISSUED'
        """,
    )
    fun markVoucherRedeemed(@Param("voucherId") voucherId: Long): Int

    @Select(
        """
        SELECT id
        FROM voucher
        WHERE qr_payload = #{qrPayload}
        ORDER BY id
        LIMIT 1
        """,
    )
    fun findVoucherIdByQrPayload(@Param("qrPayload") qrPayload: String): Long?

    @Select(
        """
        SELECT id
        FROM ticket_order
        WHERE status='HOLD'
          AND expires_at < NOW(3)
        ORDER BY id
        LIMIT #{limit}
        """,
    )
    fun listExpiredHoldIds(@Param("limit") limit: Int): List<Long>

    @Select(
        """
        SELECT id, status
        FROM ticket_event
        WHERE id = #{eventId}
        LIMIT 1
        """,
    )
    fun findEvent(@Param("eventId") eventId: Long): TicketEventRow?

    @Update(
        """
        UPDATE ticket_inventory
        SET hold = hold + #{quantity}
        WHERE event_id = #{eventId}
          AND (hold + sold + #{quantity}) <= total
        """,
    )
    fun increaseInventoryHold(
        @Param("eventId") eventId: Long,
        @Param("quantity") quantity: Int,
    ): Int

    @Insert(
        """
        INSERT INTO ticket_order(user_id, event_id, qty, status, expires_at, currency, total_amount, idempotency_key)
        VALUES (#{userId}, #{eventId}, #{qty}, 'HOLD', #{expiresAt}, #{currency}, #{totalAmount}, #{idempotencyKey})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertTicketOrder(command: TicketOrderInsertCommand): Int

    @Select(
        """
        SELECT id,
               event_id AS eventId,
               qty AS quantity,
               status,
               expires_at AS expiresAt,
               total_amount AS totalAmount,
               currency
        FROM ticket_order
        WHERE id = #{orderId} AND user_id = #{userId}
        FOR UPDATE
        """,
    )
    fun findOrderForUpdate(
        @Param("orderId") orderId: Long,
        @Param("userId") userId: Long,
    ): TicketOrderRow?

    @Update(
        """
        UPDATE ticket_inventory
        SET hold = hold - #{quantity}, sold = sold + #{quantity}
        WHERE event_id = #{eventId}
          AND hold >= #{quantity}
        """,
    )
    fun moveInventoryHoldToSold(
        @Param("eventId") eventId: Long,
        @Param("quantity") quantity: Int,
    ): Int

    @Update(
        """
        UPDATE ticket_order
        SET status='CONFIRMED',
            confirmed_at=NOW(3),
            updated_at=NOW(3)
        WHERE id=#{orderId}
        """,
    )
    fun markOrderConfirmed(@Param("orderId") orderId: Long): Int

    @Select(
        """
        SELECT CONCAT('vch_', id)
        FROM voucher
        WHERE order_id = #{orderId}
        ORDER BY sequence_no
        """,
    )
    fun listVoucherIds(@Param("orderId") orderId: Long): List<String>

    @Select(
        """
        SELECT id,
               event_id AS eventId,
               qty AS quantity,
               status,
               expires_at AS expiresAt,
               total_amount AS totalAmount,
               currency
        FROM ticket_order
        WHERE id = #{orderId}
        FOR UPDATE
        """,
    )
    fun findOrderByIdForUpdate(@Param("orderId") orderId: Long): TicketOrderRow?

    @Update(
        """
        UPDATE ticket_inventory
        SET hold = hold - #{quantity}
        WHERE event_id = #{eventId}
          AND hold >= #{quantity}
        """,
    )
    fun restoreInventoryHold(
        @Param("eventId") eventId: Long,
        @Param("quantity") quantity: Int,
    ): Int

    @Update(
        """
        UPDATE ticket_order
        SET status='EXPIRED', expired_at=NOW(3), updated_at=NOW(3)
        WHERE id=#{orderId}
        """,
    )
    fun markOrderExpired(@Param("orderId") orderId: Long): Int
}
