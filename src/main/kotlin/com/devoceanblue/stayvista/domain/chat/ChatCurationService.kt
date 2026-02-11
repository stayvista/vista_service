package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class ChatCurationService(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    fun list(): List<ChatCurationRule> {
        return jdbcTemplate.query(
            """
            SELECT rule_id, doc_id, rule_type, weight, enabled, updated_at
            FROM chat_curation_rule
            ORDER BY updated_at DESC
            """.trimIndent(),
        ) { rs, _ ->
            ChatCurationRule(
                rule_id = rs.getLong("rule_id"),
                doc_id = rs.getString("doc_id"),
                rule_type = rs.getString("rule_type"),
                weight = rs.getInt("weight"),
                enabled = rs.getBoolean("enabled"),
                updated_at = rs.getTimestamp("updated_at")?.toInstant()?.toString().orEmpty(),
            )
        }
    }

    fun upsert(request: ChatCurationUpsertRequest): ChatCurationRule {
        val normalizedType = request.rule_type.trim().uppercase()
        require(normalizedType in setOf("BLACKLIST", "TOP_PICK")) {
            "rule_type must be BLACKLIST or TOP_PICK"
        }

        val updated = jdbcTemplate.update(
            """
            UPDATE chat_curation_rule
            SET weight = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP
            WHERE doc_id = ? AND rule_type = ?
            """.trimIndent(),
            request.weight.coerceIn(1, 500),
            if (request.enabled) 1 else 0,
            request.doc_id.trim(),
            normalizedType,
        )
        if (updated == 0) {
            jdbcTemplate.update(
                """
                INSERT INTO chat_curation_rule (doc_id, rule_type, weight, enabled)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                request.doc_id.trim(),
                normalizedType,
                request.weight.coerceIn(1, 500),
                if (request.enabled) 1 else 0,
            )
        }
        meterRegistry.counter("chat_curation_rule_total", "action", "upsert", "type", normalizedType).increment()
        return list().first { it.doc_id == request.doc_id.trim() && it.rule_type == normalizedType }
    }

    fun update(ruleId: Long, request: ChatCurationUpdateRequest): ChatCurationRule {
        val affected = jdbcTemplate.update(
            """
            UPDATE chat_curation_rule
            SET weight = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP
            WHERE rule_id = ?
            """.trimIndent(),
            request.weight.coerceIn(1, 500),
            if (request.enabled) 1 else 0,
            ruleId,
        )
        require(affected == 1) { "curation rule not found: $ruleId" }
        meterRegistry.counter("chat_curation_rule_total", "action", "update").increment()
        return list().first { it.rule_id == ruleId }
    }

    fun delete(ruleId: Long) {
        jdbcTemplate.update("DELETE FROM chat_curation_rule WHERE rule_id = ?", ruleId)
        meterRegistry.counter("chat_curation_rule_total", "action", "delete").increment()
    }

    fun activeRules(): ChatCurationLookup {
        val rows = jdbcTemplate.query(
            """
            SELECT doc_id, rule_type, weight
            FROM chat_curation_rule
            WHERE enabled = 1
            """.trimIndent(),
        ) { rs, _ ->
            Triple(rs.getString("doc_id"), rs.getString("rule_type"), rs.getInt("weight"))
        }

        val blacklist = mutableSetOf<String>()
        val topPicks = mutableMapOf<String, Int>()
        rows.forEach { (docId, type, weight) ->
            when (type.uppercase()) {
                "BLACKLIST" -> blacklist += docId
                "TOP_PICK" -> topPicks[docId] = weight
            }
        }

        return ChatCurationLookup(
            blacklistedDocIds = blacklist,
            topPickWeights = topPicks,
        )
    }
}

data class ChatCurationRule(
    val rule_id: Long,
    val doc_id: String,
    val rule_type: String,
    val weight: Int,
    val enabled: Boolean,
    val updated_at: String,
)

data class ChatCurationUpsertRequest(
    val doc_id: String,
    val rule_type: String,
    val weight: Int = 100,
    val enabled: Boolean = true,
)

data class ChatCurationUpdateRequest(
    val weight: Int = 100,
    val enabled: Boolean = true,
)

data class ChatCurationLookup(
    val blacklistedDocIds: Set<String>,
    val topPickWeights: Map<String, Int>,
)
