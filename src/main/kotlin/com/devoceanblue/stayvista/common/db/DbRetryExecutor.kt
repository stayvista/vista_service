package com.devoceanblue.stayvista.common.db

import io.micrometer.core.instrument.MeterRegistry
import java.sql.SQLException
import kotlin.random.Random
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

@Component
class DbRetryExecutor(
    private val meterRegistry: MeterRegistry,
) {
    fun <T> execute(action: () -> T): T {
        val backoffs = listOf(50L, 150L, 350L)
        var lastError: RuntimeException? = null

        repeat(backoffs.size) { attempt ->
            try {
                return action()
            } catch (ex: RuntimeException) {
                if (!isRetryable(ex) || attempt == backoffs.lastIndex) {
                    throw ex
                }
                lastError = ex
                val reason = reason(ex)
                meterRegistry.counter("db_retry_total", "reason", reason).increment()
                Thread.sleep(backoffs[attempt] + Random.nextLong(0, 41))
            }
        }

        throw lastError ?: IllegalStateException("Unexpected retry flow")
    }

    private fun isRetryable(ex: RuntimeException): Boolean {
        val sqlException = findSqlException(ex) ?: return false
        return sqlException.errorCode == 1213 || sqlException.errorCode == 1205 || sqlException.sqlState == "40001"
    }

    private fun reason(ex: RuntimeException): String {
        val sqlException = findSqlException(ex) ?: return "unknown"
        return when {
            sqlException.errorCode == 1213 || sqlException.sqlState == "40001" -> "deadlock"
            sqlException.errorCode == 1205 -> "lock_wait"
            else -> "unknown"
        }
    }

    private fun findSqlException(ex: Throwable?): SQLException? {
        var current = ex
        while (current != null) {
            if (current is SQLException) {
                return current
            }
            current = current.cause
        }
        return null
    }
}
