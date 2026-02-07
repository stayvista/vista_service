package com.devoceanblue.stayvista.domain.ticket

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.db.DbRetryExecutor
import com.devoceanblue.stayvista.common.idempotency.IdempotencyService
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import com.devoceanblue.stayvista.domain.payment.PaymentAuthorizationRequest
import com.devoceanblue.stayvista.domain.payment.PaymentGateway
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.Time
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class TicketService(
    private val jdbcTemplate: JdbcTemplate,
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
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val ps = connection.prepareStatement(
                """
                INSERT INTO product(partner_id, product_type, name, city, status)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            )
            ps.setLong(1, partnerId)
            ps.setString(2, request.category)
            ps.setString(3, request.name)
            ps.setString(4, request.city)
            ps.setString(5, request.status)
            ps
        }, keyHolder)
        val id = keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create ticket product")
        return id
    }

    fun createEvent(productId: Long, request: CreateTicketEventRequest): Long {
        val productExists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM product WHERE id = ?",
            Long::class.java,
            productId,
        ) ?: 0L
        if (productExists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Ticket product not found")
        }
        val keyHolder = GeneratedKeyHolder()
        val startAt = LocalDateTime.of(request.event_date, request.start_time)
        val endAt = request.end_time?.let { LocalDateTime.of(request.event_date, it) }
        jdbcTemplate.update({ connection ->
            val ps = connection.prepareStatement(
                """
                INSERT INTO ticket_event(product_id, start_time, end_time, status)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            )
            ps.setLong(1, productId)
            ps.setTimestamp(2, Timestamp.valueOf(startAt))
            ps.setTimestamp(3, endAt?.let { Timestamp.valueOf(it) })
            ps.setString(4, request.status)
            ps
        }, keyHolder)
        return keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create ticket event")
    }

    fun putInventory(eventId: Long, request: PutTicketInventoryRequest) {
        val eventExists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ticket_event WHERE id = ?",
            Long::class.java,
            eventId,
        ) ?: 0L
        if (eventExists == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Ticket event not found")
        }

        val conflict = jdbcTemplate.query(
            """
            SELECT event_id
            FROM ticket_inventory
            WHERE event_id = ?
              AND ? < (hold + sold)
            """.trimIndent(),
            { rs, _ -> rs.getLong("event_id") },
            eventId,
            request.total,
        ).firstOrNull()
        if (conflict != null) {
            throw DomainException(
                ErrorCode.INVENTORY_TOTAL_BELOW_COMMITTED,
                "Inventory total cannot be lower than hold + sold",
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO ticket_inventory(event_id, total, hold, sold)
            VALUES (?, ?, 0, 0)
            ON DUPLICATE KEY UPDATE total = VALUES(total), updated_at = NOW(3)
            """.trimIndent(),
            eventId,
            request.total,
        )
    }

    fun listProducts(): TicketProductListData {
        val rows = jdbcTemplate.query(
            """
            SELECT id, name, product_type, city, status
            FROM product
            WHERE status='ACTIVE'
            ORDER BY id DESC
            """.trimIndent(),
            { rs, _ ->
                TicketProductSummary(
                    product_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    category = rs.getString("product_type"),
                    city = rs.getString("city"),
                    status = rs.getString("status"),
                )
            },
        )
        return TicketProductListData(rows)
    }

    fun getProduct(productId: Long): TicketProductDetail {
        return jdbcTemplate.query(
            """
            SELECT id, name, product_type, city, status
            FROM product
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                TicketProductDetail(
                    product_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    category = rs.getString("product_type"),
                    city = rs.getString("city"),
                    status = rs.getString("status"),
                )
            },
            productId,
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Ticket product not found")
    }

    fun listEvents(productId: Long?, date: LocalDate?): TicketEventListData {
        val params = mutableListOf<Any?>()
        val where = mutableListOf<String>()
        if (productId != null) {
            where += "te.product_id = ?"
            params += productId
        }
        if (date != null) {
            where += "DATE(te.start_time) = ?"
            params += Date.valueOf(date)
        }
        val sql = buildString {
            append(
                """
                SELECT te.id, te.product_id, te.start_time, te.end_time, te.status,
                       COALESCE(ti.total, 0) AS total, COALESCE(ti.hold, 0) AS hold, COALESCE(ti.sold, 0) AS sold
                FROM ticket_event te
                LEFT JOIN ticket_inventory ti ON ti.event_id = te.id
                """.trimIndent(),
            )
            if (where.isNotEmpty()) {
                append(" WHERE ")
                append(where.joinToString(" AND "))
            }
            append(" ORDER BY te.start_time")
        }
        val rows = jdbcTemplate.query(sql, { rs, _ ->
            TicketEventSummary(
                event_id = rs.getLong("id"),
                product_id = rs.getLong("product_id"),
                event_date = rs.getTimestamp("start_time").toLocalDateTime().toLocalDate(),
                start_time = rs.getTimestamp("start_time").toLocalDateTime().toLocalTime(),
                end_time = rs.getTimestamp("end_time")?.toLocalDateTime()?.toLocalTime(),
                status = rs.getString("status"),
                total = rs.getInt("total"),
                hold = rs.getInt("hold"),
                sold = rs.getInt("sold"),
            )
        }, *params.toTypedArray())
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
        val foundOrder = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ticket_order
            WHERE id = ? AND user_id = ?
            """.trimIndent(),
            Long::class.java,
            orderId,
            userId,
        ) ?: 0L
        if (foundOrder == 0L) {
            throw DomainException(ErrorCode.NOT_FOUND, "Ticket order not found")
        }

        val vouchers = jdbcTemplate.query(
            """
            SELECT id, sequence_no, status, qr_payload, issued_at, redeemed_at
            FROM voucher
            WHERE order_id = ? AND user_id = ?
            ORDER BY sequence_no
            """.trimIndent(),
            { rs, _ ->
                TicketVoucherSummary(
                    voucher_id = "vch_${rs.getLong("id")}",
                    sequence_no = rs.getInt("sequence_no"),
                    status = rs.getString("status"),
                    qr_payload = rs.getString("qr_payload"),
                    issued_at = rs.getTimestamp("issued_at")?.toInstant()?.toString(),
                    redeemed_at = rs.getTimestamp("redeemed_at")?.toInstant()?.toString(),
                )
            },
            orderId,
            userId,
        )

        return TicketVoucherListData(
            order_id = "tord_$orderId",
            items = vouchers,
        )
    }

    fun validateVoucher(request: ValidateVoucherRequest): VoucherValidateData {
        val voucher = jdbcTemplate.query(
            """
            SELECT id, status
            FROM voucher
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                VoucherRow(
                    id = rs.getLong("id"),
                    status = rs.getString("status"),
                )
            },
            request.voucher_id.removePrefix("vch_").toLongOrNull()
                ?: throw DomainException(ErrorCode.VALIDATION_ERROR, "Invalid voucher_id"),
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Voucher not found")

        if (voucher.status == "REDEEMED") {
            throw DomainException(ErrorCode.ALREADY_USED, "Voucher already redeemed")
        }
        if (voucher.status == "EXPIRED") {
            throw DomainException(ErrorCode.EXPIRED, "Voucher expired")
        }

        jdbcTemplate.update(
            """
            UPDATE voucher
            SET status='REDEEMED', redeemed_at=NOW(3)
            WHERE id=? AND status='ISSUED'
            """.trimIndent(),
            voucher.id,
        )
        return VoucherValidateData(
            voucher_id = "vch_${voucher.id}",
            result = "VALID",
        )
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 45000)
    fun expireTicketHolds() {
        val ids = jdbcTemplate.query(
            """
            SELECT id
            FROM ticket_order
            WHERE status='HOLD'
              AND expires_at < NOW(3)
            ORDER BY id
            LIMIT 200
            """.trimIndent(),
            { rs, _ -> rs.getLong("id") },
        )
        ids.forEach { orderId ->
            transactionTemplate.execute {
                expireOne(orderId)
            }
        }
    }

    private fun holdTx(userId: Long, idempotencyKey: String, request: TicketHoldRequest): TicketHoldData {
        domainSupportService.ensureUserExists(userId)
        val event = jdbcTemplate.query(
            """
            SELECT id, status
            FROM ticket_event
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                TicketEventRow(
                    id = rs.getLong("id"),
                    status = rs.getString("status"),
                )
            },
            request.event_id,
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Ticket event not found")
        if (event.status != "ACTIVE") {
            throw DomainException(ErrorCode.NOT_FOUND, "Ticket event not available")
        }

        val affected = jdbcTemplate.update(
            """
            UPDATE ticket_inventory
            SET hold = hold + ?
            WHERE event_id = ?
              AND (hold + sold + ?) <= total
            """.trimIndent(),
            request.quantity,
            request.event_id,
            request.quantity,
        )
        if (affected != 1) {
            throw DomainException(ErrorCode.TICKET_SOLD_OUT, "Ticket sold out")
        }

        val expiresAt = Instant.now(clock).plusSeconds(holdTtlMinutes * 60)
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val ps = connection.prepareStatement(
                """
                INSERT INTO ticket_order(user_id, event_id, qty, status, expires_at, currency, total_amount, idempotency_key)
                VALUES (?, ?, ?, 'HOLD', ?, ?, ?, ?)
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            )
            ps.setLong(1, userId)
            ps.setLong(2, request.event_id)
            ps.setInt(3, request.quantity)
            ps.setTimestamp(4, Timestamp.from(expiresAt))
            ps.setString(5, request.price.currency)
            ps.setLong(6, request.price.amount_total)
            ps.setString(7, idempotencyKey)
            ps
        }, keyHolder)
        val orderId = keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create order")

        return TicketHoldData(
            order_id = "tord_$orderId",
            status = "HOLD",
            expires_at = expiresAt.toString(),
        )
    }

    private fun confirmTx(userId: Long, orderId: Long, request: TicketConfirmRequest): TicketConfirmData {
        domainSupportService.ensureUserExists(userId)
        val order = jdbcTemplate.query(
            """
            SELECT id, event_id, qty, status, expires_at, total_amount, currency
            FROM ticket_order
            WHERE id = ? AND user_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                TicketOrderRow(
                    id = rs.getLong("id"),
                    eventId = rs.getLong("event_id"),
                    quantity = rs.getInt("qty"),
                    status = rs.getString("status"),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                    totalAmount = rs.getLong("total_amount"),
                    currency = rs.getString("currency"),
                )
            },
            orderId,
            userId,
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Ticket order not found")

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

        val moved = jdbcTemplate.update(
            """
            UPDATE ticket_inventory
            SET hold = hold - ?, sold = sold + ?
            WHERE event_id = ?
              AND hold >= ?
            """.trimIndent(),
            order.quantity,
            order.quantity,
            order.eventId,
            order.quantity,
        )
        if (moved != 1) {
            throw DomainException(ErrorCode.INVENTORY_INVARIANT_VIOLATION, "Inventory hold is not sufficient")
        }

        jdbcTemplate.update(
            """
            UPDATE ticket_order
            SET status='CONFIRMED',
                confirmed_at=NOW(3),
                updated_at=NOW(3)
            WHERE id=?
            """.trimIndent(),
            order.id,
        )

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

        val voucherIds = jdbcTemplate.query(
            """
            SELECT id
            FROM voucher
            WHERE order_id = ?
            ORDER BY sequence_no
            """.trimIndent(),
            { rs, _ -> "vch_${rs.getLong("id")}" },
            order.id,
        )

        return TicketConfirmData(
            order_id = "tord_${order.id}",
            status = "CONFIRMED",
            voucher_ids = voucherIds,
        )
    }

    private fun expireOne(orderId: Long) {
        val order = jdbcTemplate.query(
            """
            SELECT id, event_id, qty, status, expires_at, total_amount, currency
            FROM ticket_order
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                TicketOrderRow(
                    id = rs.getLong("id"),
                    eventId = rs.getLong("event_id"),
                    quantity = rs.getInt("qty"),
                    status = rs.getString("status"),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                    totalAmount = rs.getLong("total_amount"),
                    currency = rs.getString("currency"),
                )
            },
            orderId,
        ).firstOrNull() ?: return

        if (order.status != "HOLD") return
        if (order.expiresAt == null || order.expiresAt.isAfter(Instant.now(clock))) return

        val restored = jdbcTemplate.update(
            """
            UPDATE ticket_inventory
            SET hold = hold - ?
            WHERE event_id = ?
              AND hold >= ?
            """.trimIndent(),
            order.quantity,
            order.eventId,
            order.quantity,
        )
        if (restored == 1) {
            jdbcTemplate.update(
                """
                UPDATE ticket_order
                SET status='EXPIRED', expired_at=NOW(3), updated_at=NOW(3)
                WHERE id=?
                """.trimIndent(),
                order.id,
            )
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
)

data class TicketProductDetail(
    val product_id: Long,
    val name: String,
    val category: String,
    val city: String?,
    val status: String,
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
    val voucher_id: String,
)

data class VoucherValidateData(
    val voucher_id: String,
    val result: String,
)

private data class TicketEventRow(
    val id: Long,
    val status: String,
)

private data class TicketOrderRow(
    val id: Long,
    val eventId: Long,
    val quantity: Int,
    val status: String,
    val expiresAt: Instant?,
    val totalAmount: Long,
    val currency: String,
)

private data class VoucherRow(
    val id: Long,
    val status: String,
)
