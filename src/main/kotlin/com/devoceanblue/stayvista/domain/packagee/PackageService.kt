package com.devoceanblue.stayvista.domain.packagee

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.idempotency.IdempotencyService
import com.devoceanblue.stayvista.domain.booking.BookingCancelRequest
import com.devoceanblue.stayvista.domain.booking.BookingConfirmRequest
import com.devoceanblue.stayvista.domain.booking.BookingHoldRequest
import com.devoceanblue.stayvista.domain.booking.BookingMoney
import com.devoceanblue.stayvista.domain.booking.BookingService
import com.devoceanblue.stayvista.domain.booking.BookingGuestRequest
import com.devoceanblue.stayvista.domain.ticket.TicketConfirmRequest
import com.devoceanblue.stayvista.domain.ticket.TicketHoldPrice
import com.devoceanblue.stayvista.domain.ticket.TicketHoldRequest
import com.devoceanblue.stayvista.domain.ticket.TicketService
import java.sql.PreparedStatement
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class PackageService(
    private val jdbcTemplate: JdbcTemplate,
    private val bookingService: BookingService,
    private val ticketService: TicketService,
    private val idempotencyService: IdempotencyService,
    private val transactionTemplate: TransactionTemplate,
) {
    fun createPackage(request: CreatePackageRequest): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val ps = connection.prepareStatement(
                """
                INSERT INTO package_product(name, status, currency, amount_total)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            )
            ps.setString(1, request.name)
            ps.setString(2, request.status)
            ps.setString(3, request.price.currency)
            ps.setLong(4, request.price.amount_total)
            ps
        }, keyHolder)
        val packageId = keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create package")

        request.components.forEach { component ->
            jdbcTemplate.update(
                """
                INSERT INTO package_product_component(
                    package_id, component_type, room_type_id, ticket_event_id, nights, rooms, quantity
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                packageId,
                component.type,
                component.room_type_id,
                component.event_id,
                component.nights,
                component.rooms,
                component.quantity,
            )
        }
        return packageId
    }

    fun getPackage(packageId: Long): PackageDetail {
        val pack = jdbcTemplate.query(
            """
            SELECT id, name, status, currency, amount_total
            FROM package_product
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                PackageBase(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    status = rs.getString("status"),
                    currency = rs.getString("currency"),
                    amount = rs.getLong("amount_total"),
                )
            },
            packageId,
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Package not found")
        return PackageDetail(
            package_id = pack.id,
            name = pack.name,
            status = pack.status,
            components = components(pack.id),
            price = PackagePrice(pack.currency, pack.amount),
        )
    }

    fun listPackages(): PackageListData {
        val items = jdbcTemplate.query(
            """
            SELECT id, name, status, currency, amount_total
            FROM package_product
            WHERE status='ACTIVE'
            ORDER BY id DESC
            """.trimIndent(),
            { rs, _ ->
                PackageSummary(
                    package_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    status = rs.getString("status"),
                    price = PackagePrice(
                        currency = rs.getString("currency"),
                        amount_total = rs.getLong("amount_total"),
                    ),
                )
            },
        )
        return PackageListData(items)
    }

    fun hold(userId: Long, packageId: Long, idempotencyKey: String, request: PackageHoldRequest): PackageHoldData {
        return idempotencyService.execute(
            scope = "PACKAGE_HOLD",
            idempotencyKey = idempotencyKey,
            payload = mapOf("user_id" to userId, "package_id" to packageId, "request" to request),
            responseType = PackageHoldData::class.java,
        ) {
            val components = components(packageId)
            val accommodation = components.firstOrNull { it.type == "ACCOMMODATION" }
                ?: throw DomainException(ErrorCode.NOT_FOUND, "Accommodation component missing")
            val ticket = components.firstOrNull { it.type == "TICKET" }
                ?: throw DomainException(ErrorCode.NOT_FOUND, "Ticket component missing")

            val packageOrderId = transactionTemplate.execute {
                createPackageOrder(packageId, userId)
            } ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create package order")

            val bookingIdem = "pkg-hold-booking-$packageOrderId"
            val ticketIdem = "pkg-hold-ticket-$packageOrderId"
            val bookingHold = try {
                bookingService.createHold(
                    userId = userId,
                    idempotencyKey = bookingIdem,
                    request = BookingHoldRequest(
                        room_type_id = accommodation.room_type_id ?: throw DomainException(ErrorCode.NOT_FOUND, "room_type_id missing"),
                        check_in = request.check_in,
                        check_out = request.check_out,
                        rooms = request.rooms,
                        guests = BookingGuestRequest(adults = 2, children = 0),
                        price = BookingMoney(currency = "KRW", amount_total = 0),
                    ),
                )
            } catch (ex: RuntimeException) {
                transactionTemplate.execute {
                    updatePackageOrderStatus(packageOrderId, "FAILED")
                }
                throw ex
            }

            val ticketHold = try {
                ticketService.hold(
                    userId = userId,
                    idempotencyKey = ticketIdem,
                    request = TicketHoldRequest(
                        event_id = ticket.event_id ?: throw DomainException(ErrorCode.NOT_FOUND, "event_id missing"),
                        quantity = request.ticket_quantity,
                        price = TicketHoldPrice(currency = "KRW", amount_total = 0),
                    ),
                )
            } catch (ex: RuntimeException) {
                bookingService.cancel(
                    userId = userId,
                    rawBookingId = bookingHold.booking_id,
                    idempotencyKey = "pkg-compensate-$packageOrderId",
                    request = BookingCancelRequest("PACKAGE_HOLD_FAILED"),
                )
                transactionTemplate.execute {
                    updatePackageOrderStatus(packageOrderId, "FAILED")
                }
                throw ex
            }

            val bookingNumericId = bookingHold.booking_id.removePrefix("bkg_").toLong()
            val ticketNumericId = ticketHold.order_id.removePrefix("tord_").toLong()
            val expiresAt = minOf(
                Instant.parse(bookingHold.expires_at),
                Instant.parse(ticketHold.expires_at),
            )

            transactionTemplate.execute {
                jdbcTemplate.update(
                    """
                    UPDATE package_order
                    SET status='HOLD',
                        booking_id=?,
                        ticket_order_id=?,
                        expires_at=?,
                        updated_at=NOW(3)
                    WHERE id=?
                    """.trimIndent(),
                    bookingNumericId,
                    ticketNumericId,
                    java.sql.Timestamp.from(expiresAt),
                    packageOrderId,
                )
            }

            PackageHoldData(
                package_order_id = "pkg_$packageOrderId",
                status = "HOLD",
                components = PackageHoldComponents(
                    booking_id = bookingHold.booking_id,
                    ticket_order_id = ticketHold.order_id,
                ),
                expires_at = expiresAt.toString(),
            )
        }
    }

    fun confirm(userId: Long, packageId: Long, idempotencyKey: String, request: PackageConfirmRequest): PackageConfirmData {
        return idempotencyService.execute(
            scope = "PACKAGE_CONFIRM",
            idempotencyKey = idempotencyKey,
            payload = mapOf("user_id" to userId, "package_id" to packageId, "request" to request),
            responseType = PackageConfirmData::class.java,
        ) {
            val packageOrderId = request.package_order_id.removePrefix("pkg_").toLongOrNull()
                ?: throw DomainException(ErrorCode.VALIDATION_ERROR, "Invalid package_order_id")
            val row = jdbcTemplate.query(
                """
                SELECT id, status, booking_id, ticket_order_id, expires_at
                FROM package_order
                WHERE id = ? AND package_id = ? AND user_id = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    PackageOrderRow(
                        id = rs.getLong("id"),
                        status = rs.getString("status"),
                        bookingId = rs.getLong("booking_id"),
                        ticketOrderId = rs.getLong("ticket_order_id"),
                        expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                    )
                },
                packageOrderId,
                packageId,
                userId,
            ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Package order not found")
            if (row.status != "HOLD") {
                throw DomainException(ErrorCode.CONFLICT, "Package order cannot be confirmed from ${row.status}")
            }
            if (row.expiresAt != null && row.expiresAt.isBefore(Instant.now())) {
                transactionTemplate.execute {
                    updatePackageOrderStatus(row.id, "EXPIRED")
                }
                throw DomainException(ErrorCode.ORDER_EXPIRED, "Package order hold expired")
            }

            val bookingId = "bkg_${row.bookingId}"
            val ticketOrderId = "tord_${row.ticketOrderId}"
            bookingService.confirm(
                userId = userId,
                rawBookingId = bookingId,
                idempotencyKey = "pkg-confirm-booking-${row.id}",
                request = BookingConfirmRequest(
                    payment_method = "CARD",
                    payment_token = request.payment_token,
                    agree_terms = true,
                ),
            )
            try {
                ticketService.confirm(
                    userId = userId,
                    rawOrderId = ticketOrderId,
                    idempotencyKey = "pkg-confirm-ticket-${row.id}",
                    request = TicketConfirmRequest(
                        payment_method = "CARD",
                        payment_token = request.payment_token,
                    ),
                )
            } catch (ex: RuntimeException) {
                bookingService.cancel(
                    userId = userId,
                    rawBookingId = bookingId,
                    idempotencyKey = "pkg-confirm-compensate-${row.id}",
                    request = BookingCancelRequest("PACKAGE_CONFIRM_FAILED"),
                )
                transactionTemplate.execute {
                    updatePackageOrderStatus(row.id, "FAILED")
                }
                throw ex
            }

            transactionTemplate.execute {
                updatePackageOrderStatus(row.id, "CONFIRMED")
            }
            PackageConfirmData(
                package_order_id = "pkg_${row.id}",
                status = "CONFIRMED",
            )
        }
    }

    private fun createPackageOrder(packageId: Long, userId: Long): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val ps = connection.prepareStatement(
                """
                INSERT INTO package_order(package_id, user_id, status)
                VALUES (?, ?, 'HOLDING')
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            )
            ps.setLong(1, packageId)
            ps.setLong(2, userId)
            ps
        }, keyHolder)
        return keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create package order")
    }

    private fun updatePackageOrderStatus(packageOrderId: Long, status: String) {
        jdbcTemplate.update(
            """
            UPDATE package_order
            SET status=?, updated_at=NOW(3)
            WHERE id=?
            """.trimIndent(),
            status,
            packageOrderId,
        )
    }

    private fun components(packageId: Long): List<PackageComponent> {
        return jdbcTemplate.query(
            """
            SELECT component_type, room_type_id, ticket_event_id, nights, rooms, quantity
            FROM package_product_component
            WHERE package_id = ?
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                PackageComponent(
                    type = rs.getString("component_type"),
                    room_type_id = rs.getLong("room_type_id").takeIf { !rs.wasNull() },
                    event_id = rs.getLong("ticket_event_id").takeIf { !rs.wasNull() },
                    nights = rs.getInt("nights").takeIf { !rs.wasNull() },
                    rooms = rs.getInt("rooms").takeIf { !rs.wasNull() },
                    quantity = rs.getInt("quantity").takeIf { !rs.wasNull() },
                )
            },
            packageId,
        )
    }
}

data class CreatePackageRequest(
    val name: String,
    val status: String = "ACTIVE",
    val price: PackagePrice,
    val components: List<PackageComponent>,
)

data class PackagePrice(
    val currency: String,
    val amount_total: Long,
)

data class PackageComponent(
    val type: String,
    val room_type_id: Long? = null,
    val event_id: Long? = null,
    val nights: Int? = null,
    val rooms: Int? = null,
    val quantity: Int? = null,
)

data class PackageSummary(
    val package_id: Long,
    val name: String,
    val status: String,
    val price: PackagePrice,
)

data class PackageListData(
    val items: List<PackageSummary>,
)

data class PackageDetail(
    val package_id: Long,
    val name: String,
    val status: String,
    val components: List<PackageComponent>,
    val price: PackagePrice,
)

data class PackageHoldRequest(
    val check_in: LocalDate,
    val check_out: LocalDate,
    val rooms: Int,
    val ticket_quantity: Int,
)

data class PackageHoldData(
    val package_order_id: String,
    val status: String,
    val components: PackageHoldComponents,
    val expires_at: String,
)

data class PackageHoldComponents(
    val booking_id: String,
    val ticket_order_id: String,
)

data class PackageConfirmRequest(
    val package_order_id: String,
    val payment_token: String,
)

data class PackageConfirmData(
    val package_order_id: String,
    val status: String,
)

private data class PackageBase(
    val id: Long,
    val name: String,
    val status: String,
    val currency: String,
    val amount: Long,
)

private data class PackageOrderRow(
    val id: Long,
    val status: String,
    val bookingId: Long,
    val ticketOrderId: Long,
    val expiresAt: Instant?,
)
