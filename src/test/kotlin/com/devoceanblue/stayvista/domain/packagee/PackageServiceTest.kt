package com.devoceanblue.stayvista.domain.packagee

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.booking.BookingService
import com.devoceanblue.stayvista.domain.ticket.TicketService
import java.sql.Timestamp
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest
class PackageServiceTest {
    @Autowired
    lateinit var packageService: PackageService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @MockitoBean
    lateinit var bookingService: BookingService

    @MockitoBean
    lateinit var ticketService: TicketService

    @BeforeEach
    fun setupSchemaAndData() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS idempotency_record (
              idem_key VARCHAR(255) NOT NULL,
              `scope` VARCHAR(100) NOT NULL,
              request_hash CHAR(64) NOT NULL,
              status VARCHAR(20) NOT NULL,
              response_json CLOB NULL,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP(),
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP(),
              PRIMARY KEY (idem_key, `scope`)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS package_order (
              id BIGINT PRIMARY KEY,
              package_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL,
              status VARCHAR(20) NOT NULL,
              booking_id BIGINT NULL,
              ticket_order_id BIGINT NULL,
              expires_at TIMESTAMP NULL,
              created_at TIMESTAMP NULL,
              updated_at TIMESTAMP NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute("ALTER TABLE package_order ADD COLUMN IF NOT EXISTS created_at TIMESTAMP")

        jdbcTemplate.update("DELETE FROM idempotency_record")
        jdbcTemplate.update("DELETE FROM package_order")

        jdbcTemplate.update(
            """
            INSERT INTO package_order(id, package_id, user_id, status, booking_id, ticket_order_id, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            9101L,
            5001L,
            1001L,
            "HOLD",
            3001L,
            4001L,
            Timestamp.from(Instant.now().minusSeconds(120)),
            Timestamp.from(Instant.now().minusSeconds(130)),
            Timestamp.from(Instant.now()),
        )
        jdbcTemplate.update(
            """
            INSERT INTO package_order(id, package_id, user_id, status, booking_id, ticket_order_id, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            9102L,
            5001L,
            1001L,
            "CONFIRMED",
            3002L,
            4002L,
            Timestamp.from(Instant.now().plusSeconds(3600)),
            Timestamp.from(Instant.now().minusSeconds(100)),
            Timestamp.from(Instant.now()),
        )
    }

    @Test
    fun `confirm should reject expired package hold before confirming components`() {
        val exception = assertThrows(DomainException::class.java) {
            packageService.confirm(
                userId = 1001L,
                packageId = 5001L,
                idempotencyKey = "idem-package-expired",
                request = PackageConfirmRequest(
                    package_order_id = "pkg_9101",
                    payment_token = "paytok_test",
                ),
            )
        }

        assertEquals(ErrorCode.ORDER_EXPIRED, exception.errorCode)
        val updatedStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM package_order WHERE id = ?",
            String::class.java,
            9101L,
        )
        assertEquals("EXPIRED", updatedStatus)
        then(bookingService).shouldHaveNoInteractions()
        then(ticketService).shouldHaveNoInteractions()
    }

    @Test
    fun `listOrders should filter by status and include identifiers`() {
        val result = packageService.listOrders(
            status = "CONFIRMED",
            limit = 20,
        )

        assertEquals(1, result.items.size)
        assertEquals("pkg_9102", result.items.first().package_order_id)
        assertEquals("bkg_3002", result.items.first().booking_id)
        assertEquals("tord_4002", result.items.first().ticket_order_id)
    }
}
