package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PromptRegistryService(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    fun list(promptKey: String?): List<PromptTemplateRecord> {
        val sql = buildString {
            append(
                """
                SELECT prompt_key, version, system_prompt, user_prompt_template, is_active, updated_at
                FROM chat_prompt_template
                """.trimIndent(),
            )
            if (!promptKey.isNullOrBlank()) {
                append(" WHERE prompt_key = ?")
            }
            append(" ORDER BY prompt_key ASC, updated_at DESC")
        }
        val args = if (promptKey.isNullOrBlank()) emptyArray() else arrayOf(promptKey)
        return jdbcTemplate.query(
            sql,
            { rs, _ ->
                PromptTemplateRecord(
                    prompt_key = rs.getString("prompt_key"),
                    version = rs.getString("version"),
                    system_prompt = rs.getString("system_prompt"),
                    user_prompt_template = rs.getString("user_prompt_template"),
                    is_active = rs.getBoolean("is_active"),
                    updated_at = rs.getTimestamp("updated_at")?.toInstant()?.toString().orEmpty(),
                )
            },
            *args,
        )
    }

    fun resolveSystemPrompt(version: String? = null): String? {
        val record = resolveRecord(version) ?: return null
        return record.system_prompt
    }

    fun resolveUserPromptTemplate(version: String? = null): String? {
        val record = resolveRecord(version) ?: return null
        return record.user_prompt_template
    }

    @Transactional
    fun upsert(request: PromptTemplateUpsertRequest): PromptTemplateRecord {
        if (request.activate) {
            jdbcTemplate.update(
                "UPDATE chat_prompt_template SET is_active = 0 WHERE prompt_key = ?",
                request.prompt_key,
            )
        }

        val updated = jdbcTemplate.update(
            """
            UPDATE chat_prompt_template
            SET system_prompt = ?,
                user_prompt_template = ?,
                is_active = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE prompt_key = ? AND version = ?
            """.trimIndent(),
            request.system_prompt,
            request.user_prompt_template,
            if (request.activate) 1 else 0,
            request.prompt_key,
            request.version,
        )
        if (updated == 0) {
            jdbcTemplate.update(
                """
                INSERT INTO chat_prompt_template (
                  prompt_key,
                  version,
                  system_prompt,
                  user_prompt_template,
                  is_active
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                request.prompt_key,
                request.version,
                request.system_prompt,
                request.user_prompt_template,
                if (request.activate) 1 else 0,
            )
        }
        meterRegistry.counter("chat_prompt_registry_total", "action", "upsert").increment()
        return getByKeyVersion(request.prompt_key, request.version) ?: throw IllegalStateException("prompt upsert failed")
    }

    @Transactional
    fun rollback(promptKey: String, version: String): PromptTemplateRecord {
        jdbcTemplate.update(
            "UPDATE chat_prompt_template SET is_active = 0 WHERE prompt_key = ?",
            promptKey,
        )
        val activated = jdbcTemplate.update(
            "UPDATE chat_prompt_template SET is_active = 1 WHERE prompt_key = ? AND version = ?",
            promptKey,
            version,
        )
        require(activated == 1) {
            "prompt template not found: $promptKey/$version"
        }
        meterRegistry.counter("chat_prompt_registry_total", "action", "rollback").increment()
        return getByKeyVersion(promptKey, version) ?: throw IllegalStateException("prompt rollback failed")
    }

    private fun resolveRecord(version: String?): PromptTemplateRecord? {
        if (!version.isNullOrBlank()) {
            return getByKeyVersion("chat-core", version)
        }

        return jdbcTemplate.query(
            """
            SELECT prompt_key, version, system_prompt, user_prompt_template, is_active, updated_at
            FROM chat_prompt_template
            WHERE prompt_key = ? AND is_active = 1
            ORDER BY updated_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                PromptTemplateRecord(
                    prompt_key = rs.getString("prompt_key"),
                    version = rs.getString("version"),
                    system_prompt = rs.getString("system_prompt"),
                    user_prompt_template = rs.getString("user_prompt_template"),
                    is_active = rs.getBoolean("is_active"),
                    updated_at = rs.getTimestamp("updated_at")?.toInstant()?.toString().orEmpty(),
                )
            },
            "chat-core",
        ).firstOrNull()
    }

    private fun getByKeyVersion(promptKey: String, version: String): PromptTemplateRecord? {
        return jdbcTemplate.query(
            """
            SELECT prompt_key, version, system_prompt, user_prompt_template, is_active, updated_at
            FROM chat_prompt_template
            WHERE prompt_key = ? AND version = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                PromptTemplateRecord(
                    prompt_key = rs.getString("prompt_key"),
                    version = rs.getString("version"),
                    system_prompt = rs.getString("system_prompt"),
                    user_prompt_template = rs.getString("user_prompt_template"),
                    is_active = rs.getBoolean("is_active"),
                    updated_at = rs.getTimestamp("updated_at")?.toInstant()?.toString().orEmpty(),
                )
            },
            promptKey,
            version,
        ).firstOrNull()
    }
}

data class PromptTemplateRecord(
    val prompt_key: String,
    val version: String,
    val system_prompt: String?,
    val user_prompt_template: String?,
    val is_active: Boolean,
    val updated_at: String,
)

data class PromptTemplateUpsertRequest(
    val prompt_key: String = "chat-core",
    val version: String,
    val system_prompt: String? = null,
    val user_prompt_template: String? = null,
    val activate: Boolean = false,
)

data class PromptTemplateRollbackRequest(
    val prompt_key: String = "chat-core",
    val version: String,
)
