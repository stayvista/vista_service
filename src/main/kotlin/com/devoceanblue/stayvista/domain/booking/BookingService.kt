package com.devoceanblue.stayvista.domain.booking

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.db.DbRetryExecutor
import com.devoceanblue.stayvista.common.idempotency.IdempotencyService
import com.devoceanblue.stayvista.common.time.DateRange
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import com.devoceanblue.stayvista.domain.payment.PaymentAuthorizationRequest
import com.devoceanblue.stayvista.domain.payment.PaymentGateway
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class BookingService(
    private val jdbcTemplate: JdbcTemplate,
    private val idempotencyService: IdempotencyService,
    private val retryExecutor: DbRetryExecutor,
    private val transactionTemplate: TransactionTemplate,
    private val domainSupportService: DomainSupportService,
    private val paymentGateway: PaymentGateway,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${stayvista.booking.hold-ttl-minutes:10}") private val holdTtlMinutes: Long,
) {
    fun createHold(userId: Long, idempotencyKey: String, request: BookingHoldRequest): BookingHoldData {
        if (request.rooms < 1) {
            throw DomainException(ErrorCode.VALIDATION_ERROR, "rooms must be >= 1")
        }
        return idempotencyService.execute(
            scope = "BOOKING_HOLD",
            idempotencyKey = idempotencyKey,
            payload = mapOf("user_id" to userId, "request" to request),
            responseType = BookingHoldData::class.java,
        ) {
            retryExecutor.execute {
                transactionTemplate.execute {
                    createHoldTx(userId, idempotencyKey, request)
                } ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create booking hold")
            }
        }.also {
            meterRegistry.counter("booking_hold_requests_total", "result", "SUCCESS").increment()
        }
    }

    fun confirm(userId: Long, rawBookingId: String, idempotencyKey: String, request: BookingConfirmRequest): BookingConfirmData {
        return idempotencyService.execute(
            scope = "BOOKING_CONFIRM",
            idempotencyKey = idempotencyKey,
            payload = mapOf("user_id" to userId, "booking_id" to rawBookingId, "request" to request),
            responseType = BookingConfirmData::class.java,
        ) {
            retryExecutor.execute {
                transactionTemplate.execute {
                    confirmTx(userId, parseBookingId(rawBookingId), request)
                } ?: throw DomainException(ErrorCode.INTERNAL, "Failed to confirm booking")
            }
        }.also {
            meterRegistry.counter("booking_confirm_requests_total", "result", "SUCCESS").increment()
        }
    }

    fun cancel(userId: Long, rawBookingId: String, idempotencyKey: String, request: BookingCancelRequest): BookingCancelData {
        return idempotencyService.execute(
            scope = "BOOKING_CANCEL",
            idempotencyKey = idempotencyKey,
            payload = mapOf("user_id" to userId, "booking_id" to rawBookingId, "request" to request),
            responseType = BookingCancelData::class.java,
        ) {
            retryExecutor.execute {
                transactionTemplate.execute {
                    cancelTx(userId, parseBookingId(rawBookingId), request)
                } ?: throw DomainException(ErrorCode.INTERNAL, "Failed to cancel booking")
            }
        }.also {
            meterRegistry.counter("booking_cancel_requests_total", "result", "SUCCESS").increment()
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    fun expireHoldsBatch() {
        val bookingIds = jdbcTemplate.query(
            """
            SELECT id
            FROM booking
            WHERE status='HOLD'
              AND expires_at < NOW(3)
            ORDER BY id
            LIMIT 200
            """.trimIndent(),
            { rs, _ -> rs.getLong("id") },
        )
        bookingIds.forEach { bookingId ->
            transactionTemplate.execute {
                expireHoldTx(bookingId)
            }
        }
    }

    private fun createHoldTx(userId: Long, idempotencyKey: String, request: BookingHoldRequest): BookingHoldData {
        val nights = DateRange.nights(request.check_in, request.check_out)
        if (nights.isEmpty()) {
            throw DomainException(ErrorCode.VALIDATION_ERROR, "check_out must be after check_in")
        }
        if (nights.size > 30) {
            throw DomainException(ErrorCode.VALIDATION_ERROR, "max stay nights exceeded")
        }

        domainSupportService.ensureUserExists(userId)

        val room = jdbcTemplate.query(
            """
            SELECT rt.id as room_type_id, rt.status as room_status, p.id as property_id, p.status as property_status
            FROM room_type rt
            JOIN property p ON p.id = rt.property_id
            WHERE rt.id = ?
            """.trimIndent(),
            { rs, _ ->
                RoomLookup(
                    roomTypeId = rs.getLong("room_type_id"),
                    roomStatus = rs.getString("room_status"),
                    propertyId = rs.getLong("property_id"),
                    propertyStatus = rs.getString("property_status"),
                )
            },
            request.room_type_id,
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Room type not found")

        if (room.roomStatus != "ACTIVE" || room.propertyStatus != "ACTIVE") {
            throw DomainException(ErrorCode.NOT_FOUND, "Room type is not available")
        }

        nights.forEach { stayDate ->
            val affected = jdbcTemplate.update(
                """
                UPDATE inventory_night
                SET hold = hold + ?
                WHERE room_type_id = ?
                  AND stay_date = ?
                  AND (hold + sold + ?) <= total
                """.trimIndent(),
                request.rooms,
                request.room_type_id,
                Date.valueOf(stayDate),
                request.rooms,
            )
            if (affected != 1) {
                meterRegistry.counter("inventory_update_failed_total").increment()
                throw DomainException(
                    ErrorCode.BOOKING_OVERBOOKED,
                    "Not enough inventory",
                    details = mapOf("stay_date" to stayDate.toString()),
                )
            }
        }

        val expiresAt = Instant.now(clock).plusSeconds(holdTtlMinutes * 60)
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val ps = connection.prepareStatement(
                """
                INSERT INTO booking(user_id, property_id, room_type_id, check_in, check_out, rooms, status, expires_at, currency, total_amount, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, 'HOLD', ?, ?, ?, ?)
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            )
            ps.setLong(1, userId)
            ps.setLong(2, room.propertyId)
            ps.setLong(3, request.room_type_id)
            ps.setDate(4, Date.valueOf(request.check_in))
            ps.setDate(5, Date.valueOf(request.check_out))
            ps.setInt(6, request.rooms)
            ps.setTimestamp(7, Timestamp.from(expiresAt))
            ps.setString(8, request.price.currency)
            ps.setLong(9, request.price.amount_total)
            ps.setString(10, idempotencyKey)
            ps
        }, keyHolder)
        val bookingId = keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create booking")

        nights.forEach { stayDate ->
            jdbcTemplate.update(
                """
                INSERT INTO booking_night(booking_id, stay_date, rooms)
                VALUES (?, ?, ?)
                """.trimIndent(),
                bookingId,
                Date.valueOf(stayDate),
                request.rooms,
            )
        }

        return BookingHoldData(
            booking_id = toBookingId(bookingId),
            status = "HOLD",
            expires_at = expiresAt.toString(),
            hold = BookingHoldPayload(
                room_type_id = request.room_type_id,
                nights = nights.map { BookingNight(stay_date = it, rooms = request.rooms) },
            ),
            price_snapshot = request.price,
        )
    }

    private fun confirmTx(userId: Long, bookingId: Long, request: BookingConfirmRequest): BookingConfirmData {
        domainSupportService.ensureUserExists(userId)
        val booking = jdbcTemplate.query(
            """
            SELECT id, room_type_id, rooms, status, expires_at, total_amount, currency
            FROM booking
            WHERE id = ? AND user_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                BookingRow(
                    id = rs.getLong("id"),
                    roomTypeId = rs.getLong("room_type_id"),
                    rooms = rs.getInt("rooms"),
                    status = rs.getString("status"),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                    totalAmount = rs.getLong("total_amount"),
                    currency = rs.getString("currency"),
                )
            },
            bookingId,
            userId,
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Booking not found")

        if (booking.status != "HOLD") {
            throw DomainException(ErrorCode.BOOKING_STATE_CONFLICT, "Booking cannot be confirmed from status ${booking.status}")
        }
        val now = Instant.now(clock)
        if (booking.expiresAt != null && booking.expiresAt.isBefore(now)) {
            throw DomainException(ErrorCode.BOOKING_EXPIRED, "Booking hold has expired")
        }
        paymentGateway.authorize(
            PaymentAuthorizationRequest(
                paymentMethod = request.payment_method,
                paymentToken = request.payment_token,
                amount = booking.totalAmount,
                currency = booking.currency,
                referenceType = "BOOKING",
                referenceId = bookingId.toString(),
            ),
        )

        val nights = jdbcTemplate.query(
            "SELECT stay_date, rooms FROM booking_night WHERE booking_id = ? ORDER BY stay_date",
            { rs, _ ->
                BookingNight(
                    stay_date = rs.getDate("stay_date").toLocalDate(),
                    rooms = rs.getInt("rooms"),
                )
            },
            bookingId,
        )
        nights.forEach { night ->
            val affected = jdbcTemplate.update(
                """
                UPDATE inventory_night
                SET hold = hold - ?, sold = sold + ?
                WHERE room_type_id = ?
                  AND stay_date = ?
                  AND hold >= ?
                """.trimIndent(),
                night.rooms,
                night.rooms,
                booking.roomTypeId,
                Date.valueOf(night.stay_date),
                night.rooms,
            )
            if (affected != 1) {
                throw DomainException(ErrorCode.INVENTORY_INVARIANT_VIOLATION, "Inventory hold is not sufficient")
            }
        }

        jdbcTemplate.update(
            """
            UPDATE booking
            SET status='CONFIRMED',
                confirmed_at=NOW(3),
                updated_at=NOW(3)
            WHERE id=?
            """.trimIndent(),
            bookingId,
        )
        domainSupportService.appendOutbox(
            aggregateType = "BOOKING",
            aggregateId = bookingId.toString(),
            eventType = "BookingConfirmed",
            payload = mapOf("booking_id" to bookingId, "payment_method" to request.payment_method),
        )

        return BookingConfirmData(
            booking_id = toBookingId(bookingId),
            status = "BOOKED",
            confirmed_at = now.toString(),
        )
    }

    private fun cancelTx(userId: Long, bookingId: Long, request: BookingCancelRequest): BookingCancelData {
        domainSupportService.ensureUserExists(userId)
        val booking = jdbcTemplate.query(
            """
            SELECT id, room_type_id, status
            FROM booking
            WHERE id = ? AND user_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                BookingCancelRow(
                    id = rs.getLong("id"),
                    roomTypeId = rs.getLong("room_type_id"),
                    status = rs.getString("status"),
                )
            },
            bookingId,
            userId,
        ).firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Booking not found")

        if (booking.status == "CANCELED" || booking.status == "EXPIRED") {
            throw DomainException(ErrorCode.BOOKING_STATE_CONFLICT, "Booking already closed")
        }

        val nights = jdbcTemplate.query(
            "SELECT stay_date, rooms FROM booking_night WHERE booking_id = ? ORDER BY stay_date",
            { rs, _ ->
                BookingNight(
                    stay_date = rs.getDate("stay_date").toLocalDate(),
                    rooms = rs.getInt("rooms"),
                )
            },
            bookingId,
        )

        nights.forEach { night ->
            val affected = when (booking.status) {
                "HOLD" -> jdbcTemplate.update(
                    """
                    UPDATE inventory_night
                    SET hold = hold - ?
                    WHERE room_type_id = ?
                      AND stay_date = ?
                      AND hold >= ?
                    """.trimIndent(),
                    night.rooms,
                    booking.roomTypeId,
                    Date.valueOf(night.stay_date),
                    night.rooms,
                )

                "CONFIRMED" -> jdbcTemplate.update(
                    """
                    UPDATE inventory_night
                    SET sold = sold - ?
                    WHERE room_type_id = ?
                      AND stay_date = ?
                      AND sold >= ?
                    """.trimIndent(),
                    night.rooms,
                    booking.roomTypeId,
                    Date.valueOf(night.stay_date),
                    night.rooms,
                )

                else -> throw DomainException(ErrorCode.BOOKING_STATE_CONFLICT, "Booking cannot be cancelled from ${booking.status}")
            }
            if (affected != 1) {
                throw DomainException(ErrorCode.INVENTORY_INVARIANT_VIOLATION, "Inventory restore failed")
            }
        }

        val now = Instant.now(clock)
        jdbcTemplate.update(
            """
            UPDATE booking
            SET status='CANCELED',
                cancelled_at=NOW(3),
                updated_at=NOW(3)
            WHERE id=?
            """.trimIndent(),
            bookingId,
        )
        domainSupportService.appendOutbox(
            aggregateType = "BOOKING",
            aggregateId = bookingId.toString(),
            eventType = "BookingCancelled",
            payload = mapOf("booking_id" to bookingId, "reason" to request.reason),
        )

        return BookingCancelData(
            booking_id = toBookingId(bookingId),
            status = "CANCELLED",
            cancelled_at = now.toString(),
        )
    }

    private fun expireHoldTx(bookingId: Long) {
        val booking = jdbcTemplate.query(
            """
            SELECT id, room_type_id, rooms, status, expires_at, total_amount, currency
            FROM booking
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                BookingRow(
                    id = rs.getLong("id"),
                    roomTypeId = rs.getLong("room_type_id"),
                    rooms = rs.getInt("rooms"),
                    status = rs.getString("status"),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                    totalAmount = rs.getLong("total_amount"),
                    currency = rs.getString("currency"),
                )
            },
            bookingId,
        ).firstOrNull() ?: return

        if (booking.status != "HOLD") {
            return
        }
        if (booking.expiresAt == null || booking.expiresAt.isAfter(Instant.now(clock))) {
            return
        }

        val nights = jdbcTemplate.query(
            "SELECT stay_date, rooms FROM booking_night WHERE booking_id = ? ORDER BY stay_date",
            { rs, _ ->
                BookingNight(
                    stay_date = rs.getDate("stay_date").toLocalDate(),
                    rooms = rs.getInt("rooms"),
                )
            },
            bookingId,
        )
        nights.forEach { night ->
            val affected = jdbcTemplate.update(
                """
                UPDATE inventory_night
                SET hold = hold - ?
                WHERE room_type_id = ?
                  AND stay_date = ?
                  AND hold >= ?
                """.trimIndent(),
                night.rooms,
                booking.roomTypeId,
                Date.valueOf(night.stay_date),
                night.rooms,
            )
            if (affected != 1) {
                meterRegistry.counter("booking_expiry_fail_total").increment()
                throw DomainException(
                    ErrorCode.INVENTORY_INVARIANT_VIOLATION,
                    "Failed to recover expired hold",
                    mapOf("booking_id" to bookingId),
                )
            }
        }
        jdbcTemplate.update(
            """
            UPDATE booking
            SET status='EXPIRED',
                expired_at=NOW(3),
                updated_at=NOW(3)
            WHERE id=?
            """.trimIndent(),
            bookingId,
        )
        meterRegistry.counter("booking_expired_total").increment()
        domainSupportService.appendOutbox(
            aggregateType = "BOOKING",
            aggregateId = bookingId.toString(),
            eventType = "BookingExpired",
            payload = mapOf("booking_id" to bookingId),
        )
    }

    private fun parseBookingId(rawBookingId: String): Long {
        return rawBookingId.removePrefix("bkg_").toLongOrNull()
            ?: throw DomainException(ErrorCode.VALIDATION_ERROR, "Invalid booking_id format")
    }

    private fun toBookingId(id: Long): String = "bkg_$id"
}

data class BookingHoldRequest(
    val room_type_id: Long,
    val check_in: LocalDate,
    val check_out: LocalDate,
    val rooms: Int,
    val guests: BookingGuestRequest,
    val price: BookingMoney,
    val user_note: String? = null,
)

data class BookingGuestRequest(
    val adults: Int,
    val children: Int,
)

data class BookingMoney(
    val currency: String,
    val amount_total: Long,
)

data class BookingHoldData(
    val booking_id: String,
    val status: String,
    val expires_at: String,
    val hold: BookingHoldPayload,
    val price_snapshot: BookingMoney,
)

data class BookingHoldPayload(
    val room_type_id: Long,
    val nights: List<BookingNight>,
)

data class BookingNight(
    val stay_date: LocalDate,
    val rooms: Int,
)

data class BookingConfirmRequest(
    val payment_method: String,
    val payment_token: String,
    val agree_terms: Boolean = true,
)

data class BookingConfirmData(
    val booking_id: String,
    val status: String,
    val confirmed_at: String,
)

data class BookingCancelRequest(
    val reason: String? = null,
)

data class BookingCancelData(
    val booking_id: String,
    val status: String,
    val cancelled_at: String,
)

private data class RoomLookup(
    val roomTypeId: Long,
    val roomStatus: String,
    val propertyId: Long,
    val propertyStatus: String,
)

private data class BookingRow(
    val id: Long,
    val roomTypeId: Long,
    val rooms: Int,
    val status: String,
    val expiresAt: Instant?,
    val totalAmount: Long,
    val currency: String,
)

private data class BookingCancelRow(
    val id: Long,
    val roomTypeId: Long,
    val status: String,
)
