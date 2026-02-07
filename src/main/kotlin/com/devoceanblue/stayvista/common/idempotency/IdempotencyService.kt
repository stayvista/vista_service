package com.devoceanblue.stayvista.common.idempotency

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class IdempotencyService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun <T : Any> execute(
        scope: String,
        idempotencyKey: String,
        payload: Any,
        responseType: Class<T>,
        action: () -> T,
    ): T {
        val requestHash = RequestHashUtil.sha256Canonical(objectMapper, payload)
        val created = tryCreate(scope, idempotencyKey, requestHash)
        if (!created) {
            return getExistingOrThrow(scope, idempotencyKey, requestHash, responseType)
        }

        return try {
            val response = action()
            jdbcTemplate.update(
                """
                UPDATE idempotency_record
                SET status='COMPLETED', response_json=?, updated_at=NOW(3)
                WHERE idem_key=? AND scope=?
                """.trimIndent(),
                objectMapper.writeValueAsString(response),
                idempotencyKey,
                scope,
            )
            response
        } catch (ex: RuntimeException) {
            jdbcTemplate.update(
                """
                UPDATE idempotency_record
                SET status='FAILED', updated_at=NOW(3)
                WHERE idem_key=? AND scope=?
                """.trimIndent(),
                idempotencyKey,
                scope,
            )
            throw ex
        }
    }

    private fun tryCreate(scope: String, idempotencyKey: String, requestHash: String): Boolean {
        return try {
            jdbcTemplate.update(
                """
                INSERT INTO idempotency_record(idem_key, scope, request_hash, status)
                VALUES (?, ?, ?, 'IN_PROGRESS')
                """.trimIndent(),
                idempotencyKey,
                scope,
                requestHash,
            )
            true
        } catch (_: DuplicateKeyException) {
            false
        }
    }

    private fun <T : Any> getExistingOrThrow(
        scope: String,
        idempotencyKey: String,
        requestHash: String,
        responseType: Class<T>,
    ): T {
        repeat(21) { attempt ->
            val row = jdbcTemplate.query(
                """
                SELECT request_hash, status, response_json
                FROM idempotency_record
                WHERE idem_key=? AND scope=?
                """.trimIndent(),
                { rs, _ ->
                    ExistingRecord(
                        requestHash = rs.getString("request_hash"),
                        status = rs.getString("status"),
                        responseJson = rs.getString("response_json"),
                    )
                },
                idempotencyKey,
                scope,
            ).firstOrNull() ?: throw DomainException(
                ErrorCode.CONFLICT,
                "Idempotency key state is missing",
            )

            if (row.requestHash != requestHash) {
                throw DomainException(
                    ErrorCode.IDEMPOTENCY_REPLAY_MISMATCH,
                    "Idempotency key was already used with a different request payload",
                )
            }

            if (row.status == "COMPLETED" && !row.responseJson.isNullOrBlank()) {
                return objectMapper.readValue(row.responseJson, responseType)
            }

            if (attempt < 20) {
                Thread.sleep(50)
            }
        }

        throw DomainException(
            ErrorCode.CONFLICT,
            "Request with same idempotency key is still in progress",
        )
    }

    private data class ExistingRecord(
        val requestHash: String,
        val status: String,
        val responseJson: String?,
    )
}
