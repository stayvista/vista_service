package com.devoceanblue.stayvista.domain.ticket

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class TicketServiceTest {
    @Autowired
    lateinit var ticketService: TicketService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setupSchemaAndData() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS ticket_order (
              id BIGINT PRIMARY KEY,
              user_id BIGINT NOT NULL,
              event_id BIGINT NULL,
              qty INT NULL,
              status VARCHAR(20) NULL,
              expires_at TIMESTAMP NULL,
              total_amount BIGINT NULL,
              currency VARCHAR(10) NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS voucher (
              id BIGINT PRIMARY KEY,
              order_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL,
              event_id BIGINT NULL,
              sequence_no INT NOT NULL,
              status VARCHAR(20) NOT NULL,
              qr_payload VARCHAR(500) NOT NULL,
              issued_at TIMESTAMP NULL,
              redeemed_at TIMESTAMP NULL
            )
            """.trimIndent(),
        )

        jdbcTemplate.update("DELETE FROM voucher")
        jdbcTemplate.update("DELETE FROM ticket_order")

        jdbcTemplate.update("INSERT INTO ticket_order(id, user_id, event_id, qty, status, expires_at, total_amount, currency) VALUES (9001, 1001, 3001, 2, 'CONFIRMED', NULL, 86000, 'KRW')")
        jdbcTemplate.update("INSERT INTO ticket_order(id, user_id, event_id, qty, status, expires_at, total_amount, currency) VALUES (9002, 2002, 3002, 1, 'HOLD', NULL, 43000, 'KRW')")
        jdbcTemplate.update(
            "INSERT INTO voucher(id, order_id, user_id, event_id, sequence_no, status, qr_payload, issued_at, redeemed_at) VALUES (7001, 9001, 1001, 3001, 1, 'ISSUED', 'qr-one', NOW(), NULL)",
        )
        jdbcTemplate.update(
            "INSERT INTO voucher(id, order_id, user_id, event_id, sequence_no, status, qr_payload, issued_at, redeemed_at) VALUES (7002, 9001, 1001, 3001, 2, 'REDEEMED', 'qr-two', NOW(), NOW())",
        )
    }

    @Test
    fun `listOrderVouchers should return user order vouchers in sequence order`() {
        val result = ticketService.listOrderVouchers(
            userId = 1001L,
            rawOrderId = "tord_9001",
        )

        assertEquals("tord_9001", result.order_id)
        assertEquals(2, result.items.size)
        assertEquals("vch_7001", result.items[0].voucher_id)
        assertEquals(1, result.items[0].sequence_no)
        assertEquals("vch_7002", result.items[1].voucher_id)
        assertEquals(2, result.items[1].sequence_no)
    }

    @Test
    fun `listOrderVouchers should fail when order does not belong to user`() {
        val exception = assertThrows(DomainException::class.java) {
            ticketService.listOrderVouchers(
                userId = 1001L,
                rawOrderId = "tord_9002",
            )
        }

        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode)
    }
}
