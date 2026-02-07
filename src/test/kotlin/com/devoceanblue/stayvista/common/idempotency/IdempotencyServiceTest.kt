package com.devoceanblue.stayvista.common.idempotency

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class IdempotencyServiceTest {
    @Autowired
    lateinit var idempotencyService: IdempotencyService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun ensureTable() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS idempotency_record (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              idem_key VARCHAR(100) NOT NULL,
              `scope` VARCHAR(50) NOT NULL,
              request_hash CHAR(64) NOT NULL,
              status VARCHAR(20) NOT NULL,
              response_json CLOB NULL,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              UNIQUE (idem_key, `scope`)
            )
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.update("DELETE FROM idempotency_record WHERE `scope` LIKE 'TEST_IDEMP_%'")
    }

    @Test
    fun `same key and same payload should reuse stored response without running action twice`() {
        val scope = "TEST_IDEMP_REPLAY"
        val key = "idem_${UUID.randomUUID()}"
        val counter = AtomicInteger(0)
        val payload = mapOf("room_type_id" to 2001, "rooms" to 1)

        val first = idempotencyService.execute(
            scope = scope,
            idempotencyKey = key,
            payload = payload,
            responseType = IdemTestResponse::class.java,
        ) {
            IdemTestResponse("OK", counter.incrementAndGet())
        }

        val second = idempotencyService.execute(
            scope = scope,
            idempotencyKey = key,
            payload = payload,
            responseType = IdemTestResponse::class.java,
        ) {
            IdemTestResponse("SHOULD_NOT_RUN", counter.incrementAndGet())
        }

        assertEquals(1, counter.get())
        assertEquals(first, second)
        assertEquals("OK", second.status)
    }

    @Test
    fun `same key and different payload should raise replay mismatch`() {
        val scope = "TEST_IDEMP_MISMATCH"
        val key = "idem_${UUID.randomUUID()}"

        idempotencyService.execute(
            scope = scope,
            idempotencyKey = key,
            payload = mapOf("event_id" to 3001, "quantity" to 1),
            responseType = IdemTestResponse::class.java,
        ) {
            IdemTestResponse("OK", 1)
        }

        val exception = assertThrows(DomainException::class.java) {
            idempotencyService.execute(
                scope = scope,
                idempotencyKey = key,
                payload = mapOf("event_id" to 3001, "quantity" to 2),
                responseType = IdemTestResponse::class.java,
            ) {
                IdemTestResponse("SHOULD_NOT_RUN", 2)
            }
        }

        assertEquals(ErrorCode.IDEMPOTENCY_REPLAY_MISMATCH, exception.errorCode)
    }
}

data class IdemTestResponse(
    val status: String,
    val value: Int,
)
