package com.devoceanblue.stayvista.domain.booking

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class BookingServiceHoldReuseTest {
    @Autowired
    lateinit var bookingService: BookingService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @BeforeEach
    fun setup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS booking_night")
        jdbcTemplate.execute("DROP TABLE IF EXISTS booking")
        jdbcTemplate.execute("DROP TABLE IF EXISTS outbox_event")
        jdbcTemplate.execute("DROP TABLE IF EXISTS idempotency_record")
        jdbcTemplate.execute("DROP TABLE IF EXISTS inventory_night")
        jdbcTemplate.execute("DROP TABLE IF EXISTS room_type")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property")
        jdbcTemplate.execute("DROP TABLE IF EXISTS user_account")

        jdbcTemplate.execute(
            """
            CREATE TABLE user_account (
              id BIGINT PRIMARY KEY,
              email VARCHAR(255) NOT NULL,
              name VARCHAR(120) NOT NULL,
              status VARCHAR(20) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE property (
              id BIGINT PRIMARY KEY,
              status VARCHAR(20) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE room_type (
              id BIGINT PRIMARY KEY,
              property_id BIGINT NOT NULL,
              status VARCHAR(20) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE inventory_night (
              room_type_id BIGINT NOT NULL,
              stay_date DATE NOT NULL,
              total INT NOT NULL,
              hold INT NOT NULL DEFAULT 0,
              sold INT NOT NULL DEFAULT 0,
              PRIMARY KEY (room_type_id, stay_date)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE idempotency_record (
              idem_key VARCHAR(120) NOT NULL,
              `scope` VARCHAR(80) NOT NULL,
              request_hash VARCHAR(80) NOT NULL,
              status VARCHAR(20) NOT NULL,
              response_json CLOB,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (idem_key, `scope`)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE booking (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              user_id BIGINT NOT NULL,
              property_id BIGINT NOT NULL,
              room_type_id BIGINT NOT NULL,
              check_in DATE NOT NULL,
              check_out DATE NOT NULL,
              rooms INT NOT NULL,
              status VARCHAR(20) NOT NULL,
              expires_at TIMESTAMP NULL,
              currency VARCHAR(10) NOT NULL,
              total_amount BIGINT NOT NULL,
              idempotency_key VARCHAR(120),
              confirmed_at TIMESTAMP NULL,
              cancelled_at TIMESTAMP NULL,
              expired_at TIMESTAMP NULL,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE booking_night (
              booking_id BIGINT NOT NULL,
              stay_date DATE NOT NULL,
              rooms INT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE outbox_event (
              event_id VARCHAR(64) PRIMARY KEY,
              aggregate_type VARCHAR(60) NOT NULL,
              aggregate_id VARCHAR(120) NOT NULL,
              event_type VARCHAR(120) NOT NULL,
              payload_json JSON NOT NULL,
              status VARCHAR(20) NOT NULL
            )
            """.trimIndent(),
        )

        jdbcTemplate.update(
            "INSERT INTO user_account(id, email, name, status) VALUES (1001, 'demo@stayvista.local', 'Demo User', 'ACTIVE')",
        )
        jdbcTemplate.update("INSERT INTO property(id, status) VALUES (7001, 'ACTIVE')")
        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status) VALUES (90001, 7001, 'ACTIVE')")
        jdbcTemplate.update(
            "INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold) VALUES (90001, DATE '2026-03-02', 1, 0, 0)",
        )
        jdbcTemplate.update(
            "INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold) VALUES (90001, DATE '2026-03-03', 1, 0, 0)",
        )
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS booking_night")
        jdbcTemplate.execute("DROP TABLE IF EXISTS booking")
        jdbcTemplate.execute("DROP TABLE IF EXISTS outbox_event")
        jdbcTemplate.execute("DROP TABLE IF EXISTS idempotency_record")
        jdbcTemplate.execute("DROP TABLE IF EXISTS inventory_night")
        jdbcTemplate.execute("DROP TABLE IF EXISTS room_type")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property")
        jdbcTemplate.execute("DROP TABLE IF EXISTS user_account")
    }

    @Test
    fun `createHold should reuse existing active hold for same user and request`() {
        val request = holdRequest()

        val first = bookingService.createHold(1001L, "hold-reuse-1", request)
        val second = bookingService.createHold(1001L, "hold-reuse-2", request)

        assertEquals(first.booking_id, second.booking_id)
        assertEquals(
            1L,
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking WHERE status = 'HOLD'", Long::class.java) ?: 0L,
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT hold FROM inventory_night WHERE room_type_id = 90001 AND stay_date = DATE '2026-03-02'",
                Int::class.java,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT hold FROM inventory_night WHERE room_type_id = 90001 AND stay_date = DATE '2026-03-03'",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `createHold should release expired hold first and then create a new hold`() {
        jdbcTemplate.update(
            """
            INSERT INTO booking(
              id, user_id, property_id, room_type_id, check_in, check_out, rooms, status, expires_at, currency, total_amount, idempotency_key
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 'HOLD', ?, 'KRW', ?, ?)
            """.trimIndent(),
            501L,
            1001L,
            7001L,
            90001L,
            java.sql.Date.valueOf(LocalDate.parse("2026-03-02")),
            java.sql.Date.valueOf(LocalDate.parse("2026-03-04")),
            1,
            Timestamp.from(Instant.now().minusSeconds(600)),
            120000L,
            "expired-legacy-hold",
        )
        jdbcTemplate.update(
            "INSERT INTO booking_night(booking_id, stay_date, rooms) VALUES (501, DATE '2026-03-02', 1)",
        )
        jdbcTemplate.update(
            "INSERT INTO booking_night(booking_id, stay_date, rooms) VALUES (501, DATE '2026-03-03', 1)",
        )
        jdbcTemplate.update(
            "UPDATE inventory_night SET hold = 1 WHERE room_type_id = 90001 AND stay_date IN (DATE '2026-03-02', DATE '2026-03-03')",
        )

        val next = bookingService.createHold(1001L, "hold-release-expired", holdRequest())

        assertNotEquals("bkg_501", next.booking_id)
        assertEquals(
            "EXPIRED",
            jdbcTemplate.queryForObject("SELECT status FROM booking WHERE id = 501", String::class.java),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT hold FROM inventory_night WHERE room_type_id = 90001 AND stay_date = DATE '2026-03-02'",
                Int::class.java,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT hold FROM inventory_night WHERE room_type_id = 90001 AND stay_date = DATE '2026-03-03'",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `reused hold should confirm once and prevent second hold when inventory is fully sold`() {
        val request = holdRequest()
        val first = bookingService.createHold(1001L, "hold-reuse-confirm-1", request)
        val reused = bookingService.createHold(1001L, "hold-reuse-confirm-2", request)
        assertEquals(first.booking_id, reused.booking_id)

        val confirmed = bookingService.confirm(
            userId = 1001L,
            rawBookingId = first.booking_id,
            idempotencyKey = "confirm-reuse-confirm-1",
            request = BookingConfirmRequest(
                payment_method = "CARD",
                payment_token = "ok-token",
            ),
        )
        assertEquals("BOOKED", confirmed.status)

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT hold FROM inventory_night WHERE room_type_id = 90001 AND stay_date = DATE '2026-03-02'",
                Int::class.java,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT sold FROM inventory_night WHERE room_type_id = 90001 AND stay_date = DATE '2026-03-02'",
                Int::class.java,
            ),
        )
        assertEquals(
            "CONFIRMED",
            jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE id = ?",
                String::class.java,
                first.booking_id.removePrefix("bkg_").toLong(),
            ),
        )

        val beforeOverbooked = meterRegistry
            .find("booking_overbooked_total")
            .tag("stage", "hold")
            .counter()
            ?.count()
            ?: 0.0
        val overbooked = assertThrows(DomainException::class.java) {
            bookingService.createHold(1001L, "hold-reuse-confirm-3", request)
        }
        assertEquals(ErrorCode.BOOKING_OVERBOOKED, overbooked.errorCode)
        val afterOverbooked = meterRegistry
            .find("booking_overbooked_total")
            .tag("stage", "hold")
            .counter()
            ?.count()
            ?: 0.0
        assertEquals(beforeOverbooked + 1.0, afterOverbooked, 0.000001)
    }

    private fun holdRequest(): BookingHoldRequest {
        return BookingHoldRequest(
            room_type_id = 90001L,
            check_in = LocalDate.parse("2026-03-02"),
            check_out = LocalDate.parse("2026-03-04"),
            rooms = 1,
            guests = BookingGuestRequest(adults = 2, children = 0),
            price = BookingMoney(currency = "KRW", amount_total = 120000L),
        )
    }
}
