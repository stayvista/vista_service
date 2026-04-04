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
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class BookingService(
    private val mapper: BookingMapper,
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
            meterRegistry.counter("booking_funnel_stage_total", "stage", "hold", "result", "SUCCESS").increment()
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
            meterRegistry.counter("booking_funnel_stage_total", "stage", "confirm", "result", "SUCCESS").increment()
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
        val bookingIds = mapper.listExpiredHoldIds(limit = 200)
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

        val room = mapper.findRoomLookup(request.room_type_id)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "Room type not found")

        if (room.roomStatus != "ACTIVE" || room.propertyStatus != "ACTIVE") {
            throw DomainException(ErrorCode.NOT_FOUND, "Room type is not available")
        }

        releaseExpiredHoldsForWindow(
            roomTypeId = request.room_type_id,
            checkIn = request.check_in,
            checkOut = request.check_out,
        )

        val reusable = findReusableHold(
            userId = userId,
            roomTypeId = request.room_type_id,
            checkIn = request.check_in,
            checkOut = request.check_out,
            rooms = request.rooms,
        )
        if (reusable != null) {
            val refreshedExpiresAt = Instant.now(clock).plusSeconds(holdTtlMinutes * 60)
            mapper.refreshReusableHold(
                bookingId = reusable.id,
                expiresAt = Timestamp.from(refreshedExpiresAt),
                currency = request.price.currency,
                totalAmount = request.price.amount_total,
            )
            val heldNights = mapper.listBookingNights(reusable.id).map { it.toData() }
            meterRegistry.counter("booking_hold_reused_total").increment()
            return BookingHoldData(
                booking_id = toBookingId(reusable.id),
                status = "HOLD",
                expires_at = refreshedExpiresAt.toString(),
                hold = BookingHoldPayload(
                    room_type_id = request.room_type_id,
                    nights = heldNights,
                ),
                price_snapshot = request.price,
            )
        }

        nights.forEach { stayDate ->
            val affected = mapper.increaseInventoryHold(
                roomTypeId = request.room_type_id,
                stayDate = Date.valueOf(stayDate),
                rooms = request.rooms,
            )
            if (affected != 1) {
                meterRegistry.counter("inventory_update_failed_total").increment()
                meterRegistry.counter("booking_overbooked_total", "stage", "hold").increment()
                throw DomainException(
                    ErrorCode.BOOKING_OVERBOOKED,
                    "Not enough inventory",
                    details = mapOf("stay_date" to stayDate.toString()),
                )
            }
        }

        val expiresAt = Instant.now(clock).plusSeconds(holdTtlMinutes * 60)
        val command = BookingInsertCommand(
            userId = userId,
            propertyId = room.propertyId,
            roomTypeId = request.room_type_id,
            checkIn = Date.valueOf(request.check_in),
            checkOut = Date.valueOf(request.check_out),
            rooms = request.rooms,
            expiresAt = Timestamp.from(expiresAt),
            currency = request.price.currency,
            totalAmount = request.price.amount_total,
            idempotencyKey = idempotencyKey,
        )
        mapper.insertBooking(command)
        val bookingId = command.id ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create booking")

        nights.forEach { stayDate ->
            mapper.insertBookingNight(
                bookingId = bookingId,
                stayDate = Date.valueOf(stayDate),
                rooms = request.rooms,
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

    private fun releaseExpiredHoldsForWindow(roomTypeId: Long, checkIn: LocalDate, checkOut: LocalDate) {
        val expiredBookingIds = mapper.listExpiredHoldIdsForWindow(
            roomTypeId = roomTypeId,
            checkIn = Date.valueOf(checkIn),
            checkOut = Date.valueOf(checkOut),
            limit = 100,
        )
        expiredBookingIds.forEach { expireHoldTx(it) }
        if (expiredBookingIds.isNotEmpty()) {
            meterRegistry.counter("booking_hold_expired_released_total").increment(expiredBookingIds.size.toDouble())
        }
    }

    private fun findReusableHold(
        userId: Long,
        roomTypeId: Long,
        checkIn: LocalDate,
        checkOut: LocalDate,
        rooms: Int,
    ): ReusableHoldRow? {
        return mapper.findReusableHold(
            userId = userId,
            roomTypeId = roomTypeId,
            checkIn = Date.valueOf(checkIn),
            checkOut = Date.valueOf(checkOut),
            rooms = rooms,
        )
    }

    private fun confirmTx(userId: Long, bookingId: Long, request: BookingConfirmRequest): BookingConfirmData {
        domainSupportService.ensureUserExists(userId)
        val booking = mapper.findBookingForUpdate(
            bookingId = bookingId,
            userId = userId,
        )?.toRecord() ?: throw DomainException(ErrorCode.NOT_FOUND, "Booking not found")

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

        val nights = mapper.listBookingNights(bookingId)
        nights.forEach { night ->
            val affected = mapper.moveHoldToSold(
                roomTypeId = booking.roomTypeId,
                stayDate = Date.valueOf(night.stayDate),
                rooms = night.rooms,
            )
            if (affected != 1) {
                meterRegistry.counter("booking_confirm_inventory_conflict_total").increment()
                meterRegistry.counter("booking_overbooked_total", "stage", "confirm").increment()
                throw DomainException(
                    ErrorCode.BOOKING_OVERBOOKED,
                    "Room no longer available during confirm",
                    details = mapOf("stay_date" to night.stayDate.toString()),
                )
            }
        }

        mapper.markBookingConfirmed(bookingId)
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
        val booking = mapper.findCancelBookingForUpdate(
            bookingId = bookingId,
            userId = userId,
        ) ?: throw DomainException(ErrorCode.NOT_FOUND, "Booking not found")

        if (booking.status == "CANCELED" || booking.status == "EXPIRED") {
            throw DomainException(ErrorCode.BOOKING_STATE_CONFLICT, "Booking already closed")
        }

        val nights = mapper.listBookingNights(bookingId)

        nights.forEach { night ->
            val affected = when (booking.status) {
                "HOLD" -> mapper.decreaseInventoryHold(
                    roomTypeId = booking.roomTypeId,
                    stayDate = Date.valueOf(night.stayDate),
                    rooms = night.rooms,
                )

                "CONFIRMED" -> mapper.decreaseInventorySold(
                    roomTypeId = booking.roomTypeId,
                    stayDate = Date.valueOf(night.stayDate),
                    rooms = night.rooms,
                )

                else -> throw DomainException(ErrorCode.BOOKING_STATE_CONFLICT, "Booking cannot be cancelled from ${booking.status}")
            }
            if (affected != 1) {
                throw DomainException(ErrorCode.INVENTORY_INVARIANT_VIOLATION, "Inventory restore failed")
            }
        }

        val now = Instant.now(clock)
        mapper.markBookingCancelled(bookingId)
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
        val booking = mapper.findBookingByIdForUpdate(bookingId) ?: return

        if (booking.status != "HOLD") {
            return
        }
        val bookingRecord = booking.toRecord()
        if (bookingRecord.expiresAt == null || bookingRecord.expiresAt.isAfter(Instant.now(clock))) {
            return
        }

        val nights = mapper.listBookingNights(bookingId)
        nights.forEach { night ->
            val affected = mapper.decreaseInventoryHold(
                roomTypeId = bookingRecord.roomTypeId,
                stayDate = Date.valueOf(night.stayDate),
                rooms = night.rooms,
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
        mapper.markBookingExpired(bookingId)
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

data class BookingNightRow(
    val stayDate: LocalDate,
    val rooms: Int,
) {
    fun toData(): BookingNight = BookingNight(
        stay_date = stayDate,
        rooms = rooms,
    )
}

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

data class RoomLookup(
    val roomTypeId: Long,
    val roomStatus: String,
    val propertyId: Long,
    val propertyStatus: String,
)

data class BookingRecord(
    val id: Long,
    val roomTypeId: Long,
    val rooms: Int,
    val status: String,
    val expiresAt: Instant?,
    val totalAmount: Long,
    val currency: String,
)

data class BookingRecordRow(
    val id: Long,
    val roomTypeId: Long,
    val rooms: Int,
    val status: String,
    val expiresAt: Timestamp?,
    val totalAmount: Long,
    val currency: String,
) {
    fun toRecord(): BookingRecord = BookingRecord(
        id = id,
        roomTypeId = roomTypeId,
        rooms = rooms,
        status = status,
        expiresAt = expiresAt?.toInstant(),
        totalAmount = totalAmount,
        currency = currency,
    )
}

data class BookingCancelRow(
    val id: Long,
    val roomTypeId: Long,
    val status: String,
)

data class ReusableHoldRow(
    val id: Long,
)

data class BookingInsertCommand(
    val userId: Long,
    val propertyId: Long,
    val roomTypeId: Long,
    val checkIn: Date,
    val checkOut: Date,
    val rooms: Int,
    val expiresAt: Timestamp,
    val currency: String,
    val totalAmount: Long,
    val idempotencyKey: String,
    var id: Long? = null,
)

@Mapper
interface BookingMapper {
    @Select(
        """
        SELECT id
        FROM booking
        WHERE status='HOLD'
          AND expires_at < NOW(3)
        ORDER BY id
        LIMIT #{limit}
        """,
    )
    fun listExpiredHoldIds(@Param("limit") limit: Int): List<Long>

    @Select(
        """
        SELECT rt.id AS roomTypeId,
               rt.status AS roomStatus,
               p.id AS propertyId,
               p.status AS propertyStatus
        FROM room_type rt
        JOIN property p ON p.id = rt.property_id
        WHERE rt.id = #{roomTypeId}
        LIMIT 1
        """,
    )
    fun findRoomLookup(@Param("roomTypeId") roomTypeId: Long): RoomLookup?

    @Update(
        """
        UPDATE booking
        SET expires_at = #{expiresAt},
            currency = #{currency},
            total_amount = #{totalAmount},
            updated_at = NOW(3)
        WHERE id = #{bookingId}
        """,
    )
    fun refreshReusableHold(
        @Param("bookingId") bookingId: Long,
        @Param("expiresAt") expiresAt: Timestamp,
        @Param("currency") currency: String,
        @Param("totalAmount") totalAmount: Long,
    ): Int

    @Select(
        """
        SELECT stay_date AS stayDate, rooms
        FROM booking_night
        WHERE booking_id = #{bookingId}
        ORDER BY stay_date
        """,
    )
    fun listBookingNights(@Param("bookingId") bookingId: Long): List<BookingNightRow>

    @Update(
        """
        UPDATE inventory_night
        SET hold = hold + #{rooms}
        WHERE room_type_id = #{roomTypeId}
          AND stay_date = #{stayDate}
          AND (hold + sold + #{rooms}) <= total
        """,
    )
    fun increaseInventoryHold(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("stayDate") stayDate: Date,
        @Param("rooms") rooms: Int,
    ): Int

    @Insert(
        """
        INSERT INTO booking(user_id, property_id, room_type_id, check_in, check_out, rooms, status, expires_at, currency, total_amount, idempotency_key)
        VALUES (#{userId}, #{propertyId}, #{roomTypeId}, #{checkIn}, #{checkOut}, #{rooms}, 'HOLD', #{expiresAt}, #{currency}, #{totalAmount}, #{idempotencyKey})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertBooking(command: BookingInsertCommand): Int

    @Insert(
        """
        INSERT INTO booking_night(booking_id, stay_date, rooms)
        VALUES (#{bookingId}, #{stayDate}, #{rooms})
        """,
    )
    fun insertBookingNight(
        @Param("bookingId") bookingId: Long,
        @Param("stayDate") stayDate: Date,
        @Param("rooms") rooms: Int,
    ): Int

    @Select(
        """
        SELECT DISTINCT b.id
        FROM booking b
        JOIN booking_night bn ON bn.booking_id = b.id
        WHERE b.status = 'HOLD'
          AND b.room_type_id = #{roomTypeId}
          AND b.expires_at < NOW(3)
          AND bn.stay_date >= #{checkIn}
          AND bn.stay_date < #{checkOut}
        ORDER BY b.id
        LIMIT #{limit}
        """,
    )
    fun listExpiredHoldIdsForWindow(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("checkIn") checkIn: Date,
        @Param("checkOut") checkOut: Date,
        @Param("limit") limit: Int,
    ): List<Long>

    @Select(
        """
        SELECT id
        FROM booking
        WHERE user_id = #{userId}
          AND room_type_id = #{roomTypeId}
          AND check_in = #{checkIn}
          AND check_out = #{checkOut}
          AND rooms = #{rooms}
          AND status = 'HOLD'
          AND expires_at > NOW(3)
        ORDER BY expires_at DESC, id DESC
        LIMIT 1
        FOR UPDATE
        """,
    )
    fun findReusableHold(
        @Param("userId") userId: Long,
        @Param("roomTypeId") roomTypeId: Long,
        @Param("checkIn") checkIn: Date,
        @Param("checkOut") checkOut: Date,
        @Param("rooms") rooms: Int,
    ): ReusableHoldRow?

    @Select(
        """
        SELECT id,
               room_type_id AS roomTypeId,
               rooms,
               status,
               expires_at AS expiresAt,
               total_amount AS totalAmount,
               currency
        FROM booking
        WHERE id = #{bookingId} AND user_id = #{userId}
        FOR UPDATE
        """,
    )
    fun findBookingForUpdate(
        @Param("bookingId") bookingId: Long,
        @Param("userId") userId: Long,
    ): BookingRecordRow?

    @Update(
        """
        UPDATE inventory_night
        SET hold = hold - #{rooms}, sold = sold + #{rooms}
        WHERE room_type_id = #{roomTypeId}
          AND stay_date = #{stayDate}
          AND hold >= #{rooms}
        """,
    )
    fun moveHoldToSold(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("stayDate") stayDate: Date,
        @Param("rooms") rooms: Int,
    ): Int

    @Update(
        """
        UPDATE booking
        SET status='CONFIRMED',
            confirmed_at=NOW(3),
            updated_at=NOW(3)
        WHERE id=#{bookingId}
        """,
    )
    fun markBookingConfirmed(@Param("bookingId") bookingId: Long): Int

    @Select(
        """
        SELECT id,
               room_type_id AS roomTypeId,
               status
        FROM booking
        WHERE id = #{bookingId} AND user_id = #{userId}
        FOR UPDATE
        """,
    )
    fun findCancelBookingForUpdate(
        @Param("bookingId") bookingId: Long,
        @Param("userId") userId: Long,
    ): BookingCancelRow?

    @Update(
        """
        UPDATE inventory_night
        SET hold = hold - #{rooms}
        WHERE room_type_id = #{roomTypeId}
          AND stay_date = #{stayDate}
          AND hold >= #{rooms}
        """,
    )
    fun decreaseInventoryHold(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("stayDate") stayDate: Date,
        @Param("rooms") rooms: Int,
    ): Int

    @Update(
        """
        UPDATE inventory_night
        SET sold = sold - #{rooms}
        WHERE room_type_id = #{roomTypeId}
          AND stay_date = #{stayDate}
          AND sold >= #{rooms}
        """,
    )
    fun decreaseInventorySold(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("stayDate") stayDate: Date,
        @Param("rooms") rooms: Int,
    ): Int

    @Update(
        """
        UPDATE booking
        SET status='CANCELED',
            cancelled_at=NOW(3),
            updated_at=NOW(3)
        WHERE id=#{bookingId}
        """,
    )
    fun markBookingCancelled(@Param("bookingId") bookingId: Long): Int

    @Select(
        """
        SELECT id,
               room_type_id AS roomTypeId,
               rooms,
               status,
               expires_at AS expiresAt,
               total_amount AS totalAmount,
               currency
        FROM booking
        WHERE id = #{bookingId}
        FOR UPDATE
        """,
    )
    fun findBookingByIdForUpdate(@Param("bookingId") bookingId: Long): BookingRecordRow?


    @Update(
        """
        UPDATE booking
        SET status='EXPIRED',
            expired_at=NOW(3),
            updated_at=NOW(3)
        WHERE id=#{bookingId}
        """,
    )
    fun markBookingExpired(@Param("bookingId") bookingId: Long): Int
}
