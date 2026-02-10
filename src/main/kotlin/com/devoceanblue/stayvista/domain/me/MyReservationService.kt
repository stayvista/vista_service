package com.devoceanblue.stayvista.domain.me

import com.devoceanblue.stayvista.domain.common.DomainSupportService
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class MyReservationService(
    private val jdbcTemplate: JdbcTemplate,
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
        val total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM $tableName WHERE user_id = ?",
            Long::class.java,
            userId,
        ) ?: 0L
        return total.toInt()
    }

    private fun loadBookingItems(userId: Long, limit: Int): List<ReservationWithSortAt> {
        return jdbcTemplate.query(
            """
            SELECT b.id, b.status, b.check_in, b.check_out, b.rooms, b.total_amount, b.currency,
                   b.created_at, b.expires_at, b.confirmed_at,
                   COALESCE(p.name, '숙소 정보 없음') AS property_name,
                   COALESCE(rt.name, '객실 정보 없음') AS room_name
            FROM booking b
            LEFT JOIN property p ON p.id = b.property_id
            LEFT JOIN room_type rt ON rt.id = b.room_type_id
            WHERE b.user_id = ?
            ORDER BY b.created_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH
                ReservationWithSortAt(
                    createdAt = createdAt,
                    item = MyReservationItem(
                        type = "BOOKING",
                        reservation_id = "bkg_${rs.getLong("id")}",
                        status = rs.getString("status"),
                        title = rs.getString("property_name"),
                        subtitle = "${toDateLabel(rs.getDate("check_in")?.toLocalDate())} ~ ${toDateLabel(rs.getDate("check_out")?.toLocalDate())} · ${rs.getInt("rooms")}객실 · ${rs.getString("room_name")}",
                        amount = ReservationAmount(
                            currency = rs.getString("currency") ?: "KRW",
                            amount_total = rs.getLong("total_amount"),
                        ),
                        created_at = createdAt.toString(),
                        expires_at = rs.getTimestamp("expires_at")?.toInstant()?.toString(),
                        confirmed_at = rs.getTimestamp("confirmed_at")?.toInstant()?.toString(),
                        booking_id = "bkg_${rs.getLong("id")}",
                    ),
                )
            },
            userId,
            limit,
        )
    }

    private fun loadTicketItems(userId: Long, limit: Int): List<ReservationWithSortAt> {
        return jdbcTemplate.query(
            """
            SELECT t.id, t.status, t.qty, t.total_amount, t.currency, t.created_at, t.expires_at, t.confirmed_at,
                   te.start_time AS event_start_time,
                   COALESCE(p.name, '티켓 상품 정보 없음') AS product_name
            FROM ticket_order t
            LEFT JOIN ticket_event te ON te.id = t.event_id
            LEFT JOIN product p ON p.id = te.product_id
            WHERE t.user_id = ?
            ORDER BY t.created_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH
                ReservationWithSortAt(
                    createdAt = createdAt,
                    item = MyReservationItem(
                        type = "TICKET",
                        reservation_id = "tord_${rs.getLong("id")}",
                        status = rs.getString("status"),
                        title = rs.getString("product_name"),
                        subtitle = "${toDateTimeLabel(rs.getTimestamp("event_start_time"))} · ${rs.getInt("qty")}매",
                        amount = ReservationAmount(
                            currency = rs.getString("currency") ?: "KRW",
                            amount_total = rs.getLong("total_amount"),
                        ),
                        created_at = createdAt.toString(),
                        expires_at = rs.getTimestamp("expires_at")?.toInstant()?.toString(),
                        confirmed_at = rs.getTimestamp("confirmed_at")?.toInstant()?.toString(),
                        order_id = "tord_${rs.getLong("id")}",
                    ),
                )
            },
            userId,
            limit,
        )
    }

    private fun loadPackageItems(userId: Long, limit: Int): List<ReservationWithSortAt> {
        return jdbcTemplate.query(
            """
            SELECT po.id, po.status, po.booking_id, po.ticket_order_id, po.created_at, po.updated_at, po.expires_at,
                   COALESCE(pp.name, '패키지 상품 정보 없음') AS package_name,
                   COALESCE(pp.currency, 'KRW') AS currency,
                   COALESCE(pp.amount_total, 0) AS amount_total
            FROM package_order po
            LEFT JOIN package_product pp ON pp.id = po.package_id
            WHERE po.user_id = ?
            ORDER BY po.created_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH
                val bookingId = rs.getLong("booking_id").takeIf { !rs.wasNull() }?.let { "bkg_$it" }
                val ticketOrderId = rs.getLong("ticket_order_id").takeIf { !rs.wasNull() }?.let { "tord_$it" }
                val status = rs.getString("status")
                ReservationWithSortAt(
                    createdAt = createdAt,
                    item = MyReservationItem(
                        type = "PACKAGE",
                        reservation_id = "pkg_${rs.getLong("id")}",
                        status = status,
                        title = rs.getString("package_name"),
                        subtitle = "숙소 ${bookingId ?: "-"} · 티켓 ${ticketOrderId ?: "-"}",
                        amount = ReservationAmount(
                            currency = rs.getString("currency"),
                            amount_total = rs.getLong("amount_total"),
                        ),
                        created_at = createdAt.toString(),
                        expires_at = rs.getTimestamp("expires_at")?.toInstant()?.toString(),
                        confirmed_at = if (status == "CONFIRMED") rs.getTimestamp("updated_at")?.toInstant()?.toString() else null,
                        package_order_id = "pkg_${rs.getLong("id")}",
                        booking_id = bookingId,
                        order_id = ticketOrderId,
                    ),
                )
            },
            userId,
            limit,
        )
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
