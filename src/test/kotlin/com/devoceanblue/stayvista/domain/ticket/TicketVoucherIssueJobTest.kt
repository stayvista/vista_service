package com.devoceanblue.stayvista.domain.ticket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class TicketVoucherIssueJobTest {
    @Autowired
    lateinit var job: TicketVoucherIssueJob

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setupSchemaAndData() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS voucher")
        jdbcTemplate.execute("DROP TABLE IF EXISTS outbox_event")

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS outbox_event (
              id BIGINT PRIMARY KEY,
              event_type VARCHAR(50) NOT NULL,
              payload_json CLOB NOT NULL,
              status VARCHAR(20) NOT NULL,
              published_at TIMESTAMP NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS voucher (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              order_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL,
              event_id BIGINT NOT NULL,
              sequence_no INT NOT NULL,
              status VARCHAR(20) NOT NULL,
              qr_payload VARCHAR(500) NOT NULL,
              issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP(),
              redeemed_at TIMESTAMP NULL,
              UNIQUE KEY uk_voucher_order_seq (order_id, sequence_no)
            )
            """.trimIndent(),
        )

        jdbcTemplate.update("DELETE FROM voucher")
        jdbcTemplate.update("DELETE FROM outbox_event")
    }

    @Test
    fun `issueRequestedVouchers should create vouchers and mark outbox consumed`() {
        jdbcTemplate.update(
            """
            INSERT INTO outbox_event(id, event_type, payload_json, status)
            VALUES (1, 'VoucherIssueRequested', ?, 'PUBLISHED')
            """.trimIndent(),
            """{"order_id":9001,"user_id":1001,"event_id":5001,"quantity":2}""",
        )

        job.issueRequestedVouchers()

        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM voucher WHERE order_id = 9001",
            Int::class.java,
        ) ?: 0
        assertEquals(2, count)
        val status = jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_event WHERE id = 1",
            String::class.java,
        )
        assertEquals("CONSUMED", status)
    }

    @Test
    fun `issueRequestedVouchers should resume from existing vouchers without duplicates`() {
        jdbcTemplate.update(
            """
            INSERT INTO voucher(order_id, user_id, event_id, sequence_no, status, qr_payload)
            VALUES (9101, 1001, 5001, 1, 'ISSUED', 'qr-existing')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO outbox_event(id, event_type, payload_json, status)
            VALUES (2, 'VoucherIssueRequested', ?, 'FAILED')
            """.trimIndent(),
            """{"order_id":9101,"user_id":1001,"event_id":5001,"quantity":2}""",
        )

        job.issueRequestedVouchers()
        job.issueRequestedVouchers()

        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM voucher WHERE order_id = 9101",
            Int::class.java,
        ) ?: 0
        assertEquals(2, count)
        val maxSeq = jdbcTemplate.queryForObject(
            "SELECT MAX(sequence_no) FROM voucher WHERE order_id = 9101",
            Int::class.java,
        ) ?: 0
        assertEquals(2, maxSeq)
    }
}
