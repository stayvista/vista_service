package com.devoceanblue.stayvista.common.db

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.sql.SQLException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DbRetryExecutorTest {
    @Test
    fun `should retry deadlock and succeed`() {
        val meterRegistry = SimpleMeterRegistry()
        val executor = DbRetryExecutor(meterRegistry)
        var attempts = 0

        val result = executor.execute {
            attempts += 1
            if (attempts < 3) {
                throw RuntimeException(SQLException("deadlock", "40001", 1213))
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(2.0, meterRegistry.counter("db_retry_total", "reason", "deadlock").count())
    }

    @Test
    fun `should retry lock wait timeout and succeed`() {
        val meterRegistry = SimpleMeterRegistry()
        val executor = DbRetryExecutor(meterRegistry)
        var attempts = 0

        val result = executor.execute {
            attempts += 1
            if (attempts < 2) {
                throw RuntimeException(SQLException("lock wait", "HY000", 1205))
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1.0, meterRegistry.counter("db_retry_total", "reason", "lock_wait").count())
    }

    @Test
    fun `should not retry non retryable sql exception`() {
        val meterRegistry = SimpleMeterRegistry()
        val executor = DbRetryExecutor(meterRegistry)
        var attempts = 0

        assertThrows(RuntimeException::class.java) {
            executor.execute {
                attempts += 1
                throw RuntimeException(SQLException("unique violation", "23000", 1062))
            }
        }

        assertEquals(1, attempts)
        assertEquals(0.0, meterRegistry.counter("db_retry_total", "reason", "unknown").count())
    }

    @Test
    fun `should rethrow when retryable exception keeps failing`() {
        val meterRegistry = SimpleMeterRegistry()
        val executor = DbRetryExecutor(meterRegistry)
        var attempts = 0

        assertThrows(RuntimeException::class.java) {
            executor.execute {
                attempts += 1
                throw RuntimeException(SQLException("deadlock", "40001", 1213))
            }
        }

        assertEquals(3, attempts)
        assertEquals(2.0, meterRegistry.counter("db_retry_total", "reason", "deadlock").count())
    }
}
