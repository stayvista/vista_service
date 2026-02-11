package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import kotlin.math.abs
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Service
class ChatExperimentService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    @Volatile
    private var cached: CachedConfig? = null

    fun currentConfig(): ChatExperimentConfig {
        val local = cached
        val now = System.currentTimeMillis()
        if (local != null && (now - local.loadedAtMs) < 1_000) {
            return local.config
        }
        val loaded = loadConfig()
        cached = CachedConfig(loaded, now)
        return loaded
    }

    fun assign(request: ChatRecommendRequest, sessionKey: String): ChatExperimentAssignment {
        val config = currentConfig()
        if (!config.enabled || config.rollout_percent <= 0) {
            meterRegistry.counter("chat_experiment_assignment_total", "bucket", "OFF").increment()
            return ChatExperimentAssignment(bucket = "OFF")
        }

        val context = request.context
        val subject = context["user_id"]?.toString()?.takeIf { it.isNotBlank() }
            ?: context["session_id"]?.toString()?.takeIf { it.isNotBlank() }
            ?: context["conversation_id"]?.toString()?.takeIf { it.isNotBlank() }
            ?: sessionKey
        val percent = abs(subject.hashCode()) % 100
        val bucket = if (percent < config.rollout_percent.coerceIn(0, 100)) "TREATMENT" else "CONTROL"
        meterRegistry.counter("chat_experiment_assignment_total", "bucket", bucket).increment()

        if (bucket == "TREATMENT") {
            return ChatExperimentAssignment(
                bucket = bucket,
                model_override = config.treatment_model,
                prompt_version = config.prompt_version,
                parameters = config.parameters_json,
            )
        }
        return ChatExperimentAssignment(bucket = bucket)
    }

    fun update(request: ChatExperimentUpdateRequest): ChatExperimentConfig {
        val parametersJson = objectMapper.writeValueAsString(request.parameters_json)
        val updatedRows = jdbcTemplate.update(
            """
            UPDATE chat_experiment
            SET enabled = ?,
                rollout_percent = ?,
                treatment_model = ?,
                prompt_version = ?,
                parameters_json = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE experiment_key = ?
            """.trimIndent(),
            if (request.enabled) 1 else 0,
            request.rollout_percent.coerceIn(0, 100),
            request.treatment_model,
            request.prompt_version,
            parametersJson,
            "chat-core",
        )

        if (updatedRows == 0) {
            jdbcTemplate.update(
                """
                INSERT INTO chat_experiment (
                  experiment_key,
                  enabled,
                  rollout_percent,
                  treatment_model,
                  prompt_version,
                  parameters_json
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                "chat-core",
                if (request.enabled) 1 else 0,
                request.rollout_percent.coerceIn(0, 100),
                request.treatment_model,
                request.prompt_version,
                parametersJson,
            )
        }
        meterRegistry.counter("chat_experiment_config_total", "action", "update").increment()
        val updated = loadConfig()
        cached = CachedConfig(updated, System.currentTimeMillis())
        return updated
    }

    private fun loadConfig(): ChatExperimentConfig {
        val row = jdbcTemplate.query(
            """
            SELECT experiment_key, enabled, rollout_percent, treatment_model, prompt_version, parameters_json, updated_at
            FROM chat_experiment
            WHERE experiment_key = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                val parameters = rs.getString("parameters_json")
                    ?.let { raw ->
                        runCatching {
                            objectMapper.readValue(raw, object : TypeReference<Map<String, Any?>>() {})
                        }.getOrDefault(emptyMap())
                    }
                    ?: emptyMap()

                ChatExperimentConfig(
                    experiment_key = rs.getString("experiment_key"),
                    enabled = rs.getBoolean("enabled"),
                    rollout_percent = rs.getInt("rollout_percent"),
                    treatment_model = rs.getString("treatment_model"),
                    prompt_version = rs.getString("prompt_version"),
                    parameters_json = parameters,
                    updated_at = rs.getTimestamp("updated_at")?.toInstant()?.toString().orEmpty(),
                )
            },
            "chat-core",
        ).firstOrNull()

        return row ?: ChatExperimentConfig(
            experiment_key = "chat-core",
            enabled = false,
            rollout_percent = 0,
            treatment_model = null,
            prompt_version = null,
            parameters_json = emptyMap(),
            updated_at = "",
        )
    }

    private data class CachedConfig(
        val config: ChatExperimentConfig,
        val loadedAtMs: Long,
    )
}

data class ChatExperimentConfig(
    val experiment_key: String,
    val enabled: Boolean,
    val rollout_percent: Int,
    val treatment_model: String?,
    val prompt_version: String?,
    val parameters_json: Map<String, Any?>,
    val updated_at: String,
)

data class ChatExperimentAssignment(
    val bucket: String,
    val model_override: String? = null,
    val prompt_version: String? = null,
    val parameters: Map<String, Any?> = emptyMap(),
)

data class ChatExperimentUpdateRequest(
    val enabled: Boolean = false,
    val rollout_percent: Int = 0,
    val treatment_model: String? = null,
    val prompt_version: String? = null,
    val parameters_json: Map<String, Any?> = emptyMap(),
)
