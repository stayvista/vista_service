package com.devoceanblue.stayvista.domain.me

import com.devoceanblue.stayvista.domain.common.DomainSupportService
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Service

@Service
class MyReservationService(
    private val mapper: MyReservationMapper,
    private val domainSupportService: DomainSupportService,
    private val meterRegistry: MeterRegistry,
) {
    fun listReservations(userId: Long, limit: Int): MyReservationData {
        val safeLimit = limit.coerceIn(1, 200)
        val user = domainSupportService.getActiveUser(userId)
        val bookingCount = countByTable("booking", userId)
        val ticketCount = countByTable("ticket_order", userId)
        val packageCount = countByTable("package_order", userId)

        val bookingItems = loadBookingItems(userId, safeLimit)
        val ticketItems = loadTicketItems(userId, safeLimit)
        val packageItems = loadPackageItems(userId, safeLimit)

        val items = (bookingItems + ticketItems + packageItems)
            .sortedByDescending { it.createdAt }
            .take(safeLimit)
            .map { it.item }

        meterRegistry.counter("my_reservations_requests_total").increment()

        return MyReservationData(
            user = MeUserSummary(
                user_id = user.id,
                name = user.name,
                email = user.email,
            ),
            counts = MyReservationCounts(
                total = bookingCount + ticketCount + packageCount,
                booking = bookingCount,
                ticket = ticketCount,
                package_count = packageCount,
            ),
            items = items,
        )
    }

    private fun countByTable(tableName: String, userId: Long): Int {
        return when (tableName) {
            "booking" -> mapper.countBookings(userId)
            "ticket_order" -> mapper.countTicketOrders(userId)
            "package_order" -> mapper.countPackageOrders(userId)
            else -> 0
        }
    }

    private fun loadBookingItems(userId: Long, limit: Int): List<ReservationWithSortAt> {
        return mapper.listBookings(userId = userId, limit = limit)
            .map { row ->
                val createdAt = row.createdAt?.toInstant() ?: Instant.EPOCH
                ReservationWithSortAt(
                    createdAt = createdAt,
                    item = MyReservationItem(
                        type = "BOOKING",
                        reservation_id = "bkg_${row.id}",
                        status = row.status,
                        title = row.propertyName,
                        subtitle = "${toDateLabel(row.checkIn)} ~ ${toDateLabel(row.checkOut)} · ${row.rooms}객실 · ${row.roomName}",
                        amount = ReservationAmount(
                            currency = row.currency ?: "KRW",
                            amount_total = row.totalAmount,
                        ),
                        created_at = createdAt.toString(),
                        expires_at = row.expiresAt?.toInstant()?.toString(),
                        confirmed_at = row.confirmedAt?.toInstant()?.toString(),
                        booking_id = "bkg_${row.id}",
                    ),
                )
            }
    }

    private fun loadTicketItems(userId: Long, limit: Int): List<ReservationWithSortAt> {
        return mapper.listTickets(userId = userId, limit = limit)
            .map { row ->
                val createdAt = row.createdAt?.toInstant() ?: Instant.EPOCH
                ReservationWithSortAt(
                    createdAt = createdAt,
                    item = MyReservationItem(
                        type = "TICKET",
                        reservation_id = "tord_${row.id}",
                        status = row.status,
                        title = row.productName,
                        subtitle = "${toDateTimeLabel(row.eventStartTime)} · ${row.qty}매",
                        amount = ReservationAmount(
                            currency = row.currency ?: "KRW",
                            amount_total = row.totalAmount,
                        ),
                        created_at = createdAt.toString(),
                        expires_at = row.expiresAt?.toInstant()?.toString(),
                        confirmed_at = row.confirmedAt?.toInstant()?.toString(),
                        order_id = "tord_${row.id}",
                    ),
                )
            }
    }

    private fun loadPackageItems(userId: Long, limit: Int): List<ReservationWithSortAt> {
        return mapper.listPackages(userId = userId, limit = limit)
            .map { row ->
                val createdAt = row.createdAt?.toInstant() ?: Instant.EPOCH
                val bookingId = row.bookingId?.let { "bkg_$it" }
                val ticketOrderId = row.ticketOrderId?.let { "tord_$it" }
                val status = row.status
                ReservationWithSortAt(
                    createdAt = createdAt,
                    item = MyReservationItem(
                        type = "PACKAGE",
                        reservation_id = "pkg_${row.id}",
                        status = status,
                        title = row.packageName,
                        subtitle = "숙소 ${bookingId ?: "-"} · 티켓 ${ticketOrderId ?: "-"}",
                        amount = ReservationAmount(
                            currency = row.currency,
                            amount_total = row.amountTotal,
                        ),
                        created_at = createdAt.toString(),
                        expires_at = row.expiresAt?.toInstant()?.toString(),
                        confirmed_at = if (status == "CONFIRMED") row.updatedAt?.toInstant()?.toString() else null,
                        package_order_id = "pkg_${row.id}",
                        booking_id = bookingId,
                        order_id = ticketOrderId,
                    ),
                )
            }
    }

    private fun toDateLabel(date: LocalDate?): String {
        if (date == null) return "-"
        return date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    }

    private fun toDateTimeLabel(ts: Timestamp?): String {
        if (ts == null) return "일정 정보 없음"
        val value: LocalDateTime = ts.toLocalDateTime()
        return value.format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"))
    }
}

data class MyReservationData(
    val user: MeUserSummary,
    val counts: MyReservationCounts,
    val items: List<MyReservationItem>,
)

data class MeUserSummary(
    val user_id: Long,
    val name: String,
    val email: String,
)

data class MyReservationCounts(
    val total: Int,
    val booking: Int,
    val ticket: Int,
    val package_count: Int,
)

data class MyReservationItem(
    val type: String,
    val reservation_id: String,
    val status: String,
    val title: String,
    val subtitle: String,
    val amount: ReservationAmount,
    val created_at: String,
    val expires_at: String?,
    val confirmed_at: String?,
    val booking_id: String? = null,
    val order_id: String? = null,
    val package_order_id: String? = null,
)

data class ReservationAmount(
    val currency: String,
    val amount_total: Long,
)

private data class ReservationWithSortAt(
    val createdAt: Instant,
    val item: MyReservationItem,
)

data class MyReservationBookingRow(
    val id: Long,
    val status: String,
    val checkIn: LocalDate?,
    val checkOut: LocalDate?,
    val rooms: Int,
    val totalAmount: Long,
    val currency: String?,
    val createdAt: Timestamp?,
    val expiresAt: Timestamp?,
    val confirmedAt: Timestamp?,
    val propertyName: String,
    val roomName: String,
)

data class MyReservationTicketRow(
    val id: Long,
    val status: String,
    val qty: Int,
    val totalAmount: Long,
    val currency: String?,
    val createdAt: Timestamp?,
    val expiresAt: Timestamp?,
    val confirmedAt: Timestamp?,
    val eventStartTime: Timestamp?,
    val productName: String,
)

data class MyReservationPackageRow(
    val id: Long,
    val status: String,
    val bookingId: Long?,
    val ticketOrderId: Long?,
    val createdAt: Timestamp?,
    val updatedAt: Timestamp?,
    val expiresAt: Timestamp?,
    val packageName: String,
    val currency: String,
    val amountTotal: Long,
)

@Mapper
interface MyReservationMapper {
    @Select("SELECT COUNT(*) FROM booking WHERE user_id = #{userId}")
    fun countBookings(@Param("userId") userId: Long): Int

    @Select("SELECT COUNT(*) FROM ticket_order WHERE user_id = #{userId}")
    fun countTicketOrders(@Param("userId") userId: Long): Int

    @Select("SELECT COUNT(*) FROM package_order WHERE user_id = #{userId}")
    fun countPackageOrders(@Param("userId") userId: Long): Int

    @Select(
        """
        SELECT b.id,
               b.status,
               b.check_in AS checkIn,
               b.check_out AS checkOut,
               b.rooms,
               b.total_amount AS totalAmount,
               b.currency,
               b.created_at AS createdAt,
               b.expires_at AS expiresAt,
               b.confirmed_at AS confirmedAt,
               COALESCE(p.name, '숙소 정보 없음') AS propertyName,
               COALESCE(rt.name, '객실 정보 없음') AS roomName
        FROM booking b
        LEFT JOIN property p ON p.id = b.property_id
        LEFT JOIN room_type rt ON rt.id = b.room_type_id
        WHERE b.user_id = #{userId}
        ORDER BY b.created_at DESC
        LIMIT #{limit}
        """,
    )
    fun listBookings(
        @Param("userId") userId: Long,
        @Param("limit") limit: Int,
    ): List<MyReservationBookingRow>

    @Select(
        """
        SELECT t.id,
               t.status,
               t.qty,
               t.total_amount AS totalAmount,
               t.currency,
               t.created_at AS createdAt,
               t.expires_at AS expiresAt,
               t.confirmed_at AS confirmedAt,
               te.start_time AS eventStartTime,
               COALESCE(p.name, '티켓 상품 정보 없음') AS productName
        FROM ticket_order t
        LEFT JOIN ticket_event te ON te.id = t.event_id
        LEFT JOIN product p ON p.id = te.product_id
        WHERE t.user_id = #{userId}
        ORDER BY t.created_at DESC
        LIMIT #{limit}
        """,
    )
    fun listTickets(
        @Param("userId") userId: Long,
        @Param("limit") limit: Int,
    ): List<MyReservationTicketRow>

    @Select(
        """
        SELECT po.id,
               po.status,
               po.booking_id AS bookingId,
               po.ticket_order_id AS ticketOrderId,
               po.created_at AS createdAt,
               po.updated_at AS updatedAt,
               po.expires_at AS expiresAt,
               COALESCE(pp.name, '패키지 상품 정보 없음') AS packageName,
               COALESCE(pp.currency, 'KRW') AS currency,
               COALESCE(pp.amount_total, 0) AS amountTotal
        FROM package_order po
        LEFT JOIN package_product pp ON pp.id = po.package_id
        WHERE po.user_id = #{userId}
        ORDER BY po.created_at DESC
        LIMIT #{limit}
        """,
    )
    fun listPackages(
        @Param("userId") userId: Long,
        @Param("limit") limit: Int,
    ): List<MyReservationPackageRow>
}
