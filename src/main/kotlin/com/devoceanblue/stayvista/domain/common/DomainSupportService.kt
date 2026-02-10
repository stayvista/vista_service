package com.devoceanblue.stayvista.domain.common

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class DomainSupportService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun ensureUserExists(userId: Long) {
        val found = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM user_account
            WHERE id = ?
              AND status = 'ACTIVE'
            """.trimIndent(),
            Long::class.java,
            userId,
        ) ?: 0L
        if (found <= 0L) {
            throw DomainException(ErrorCode.UNAUTHORIZED, "User not found or inactive")
        }
    }

    fun getActiveUser(userId: Long): UserAccount {
        return jdbcTemplate.query(
            """
            SELECT id, email, name, status
            FROM user_account
            WHERE id = ?
              AND status = 'ACTIVE'
            """.trimIndent(),
            { rs, _ ->
                UserAccount(
                    id = rs.getLong("id"),
                    email = rs.getString("email"),
                    name = rs.getString("name"),
                    status = rs.getString("status"),
                )
            },
            userId,
        ).firstOrNull() ?: throw DomainException(ErrorCode.UNAUTHORIZED, "User not found or inactive")
    }

    data class UserAccount(
        val id: Long,
        val email: String,
        val name: String,
        val status: String,
    )

    fun ensurePartnerExists(partnerId: Long, type: String = "HOTEL") {
        jdbcTemplate.update(
            """
            INSERT INTO partner_account(id, name, type, status)
            VALUES (?, ?, ?, 'ACTIVE')
            ON DUPLICATE KEY UPDATE name=name
            """.trimIndent(),
            partnerId,
            "Partner$partnerId",
            type,
        )
    }

    fun appendOutbox(
        aggregateType: String,
        aggregateId: String,
        eventType: String,
        payload: Any,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, payload_json, status)
            VALUES (?, ?, ?, ?, CAST(? AS JSON), 'NEW')
            """.trimIndent(),
            UUID.randomUUID().toString(),
            aggregateType,
            aggregateId,
            eventType,
            objectMapper.writeValueAsString(payload),
        )
    }
}
