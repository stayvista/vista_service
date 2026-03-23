package com.devoceanblue.stayvista.common.idempotency

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class IdempotencyService(
    private val mapper: IdempotencyMapper,
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
            mapper.markCompleted(
                scope = scope,
                idempotencyKey = idempotencyKey,
                responseJson = objectMapper.writeValueAsString(response),
            )
            response
        } catch (ex: RuntimeException) {
            mapper.markFailed(scope = scope, idempotencyKey = idempotencyKey)
            throw ex
        }
    }

    private fun tryCreate(scope: String, idempotencyKey: String, requestHash: String): Boolean {
        return try {
            mapper.insertRecord(
                scope = scope,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
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
            val row = mapper.findExisting(
                scope = scope,
                idempotencyKey = idempotencyKey,
            ) ?: throw DomainException(
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

}

@Mapper
interface IdempotencyMapper {
    @Insert(
        """
        INSERT INTO idempotency_record(idem_key, `scope`, request_hash, status)
        VALUES (#{idempotencyKey}, #{scope}, #{requestHash}, 'IN_PROGRESS')
        """,
    )
    fun insertRecord(
        @Param("scope") scope: String,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("requestHash") requestHash: String,
    ): Int

    @Update(
        """
        UPDATE idempotency_record
        SET status='COMPLETED', response_json=#{responseJson}, updated_at=NOW(3)
        WHERE idem_key=#{idempotencyKey} AND `scope`=#{scope}
        """,
    )
    fun markCompleted(
        @Param("scope") scope: String,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("responseJson") responseJson: String,
    ): Int

    @Update(
        """
        UPDATE idempotency_record
        SET status='FAILED', updated_at=NOW(3)
        WHERE idem_key=#{idempotencyKey} AND `scope`=#{scope}
        """,
    )
    fun markFailed(
        @Param("scope") scope: String,
        @Param("idempotencyKey") idempotencyKey: String,
    ): Int

    @Select(
        """
        SELECT request_hash AS requestHash, status, response_json AS responseJson
        FROM idempotency_record
        WHERE idem_key=#{idempotencyKey} AND `scope`=#{scope}
        LIMIT 1
        """,
    )
    fun findExisting(
        @Param("scope") scope: String,
        @Param("idempotencyKey") idempotencyKey: String,
    ): IdempotencyExistingRecord?
}

data class IdempotencyExistingRecord(
    val requestHash: String,
    val status: String,
    val responseJson: String?,
)
