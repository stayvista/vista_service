package com.devoceanblue.stayvista.domain.common

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
        jdbcTemplate.update(
            """
            INSERT INTO user_account(id, email, name, status)
            VALUES (?, ?, ?, 'ACTIVE')
            ON DUPLICATE KEY UPDATE name=name
            """.trimIndent(),
            userId,
            "user-$userId@local.test",
            "User$userId",
        )
    }

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
