package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import kotlin.math.abs
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Service
class ChatExperimentService(
    private val mapper: ChatExperimentMapper,
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
        val updatedRows = mapper.updateConfig(
            enabled = request.enabled,
            rolloutPercent = request.rollout_percent.coerceIn(0, 100),
            treatmentModel = request.treatment_model,
            promptVersion = request.prompt_version,
            parametersJson = parametersJson,
            experimentKey = "chat-core",
        )

        if (updatedRows == 0) {
            mapper.insertConfig(
                experimentKey = "chat-core",
                enabled = request.enabled,
                rolloutPercent = request.rollout_percent.coerceIn(0, 100),
                treatmentModel = request.treatment_model,
                promptVersion = request.prompt_version,
                parametersJson = parametersJson,
            )
        }
        meterRegistry.counter("chat_experiment_config_total", "action", "update").increment()
        val updated = loadConfig()
        cached = CachedConfig(updated, System.currentTimeMillis())
        return updated
    }

    private fun loadConfig(): ChatExperimentConfig {
        val row = mapper.findConfig("chat-core")

        return row?.toData(objectMapper) ?: ChatExperimentConfig(
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

data class ChatExperimentRow(
    val experimentKey: String,
    val enabled: Boolean,
    val rolloutPercent: Int,
    val treatmentModel: String?,
    val promptVersion: String?,
    val parametersJson: String?,
    val updatedAt: java.sql.Timestamp?,
) {
    fun toData(objectMapper: ObjectMapper): ChatExperimentConfig {
        val parameters = parametersJson
            ?.let { raw ->
                runCatching {
                    objectMapper.readValue(raw, object : TypeReference<Map<String, Any?>>() {})
                }.getOrDefault(emptyMap())
            }
            ?: emptyMap()
        return ChatExperimentConfig(
            experiment_key = experimentKey,
            enabled = enabled,
            rollout_percent = rolloutPercent,
            treatment_model = treatmentModel,
            prompt_version = promptVersion,
            parameters_json = parameters,
            updated_at = updatedAt?.toInstant()?.toString().orEmpty(),
        )
    }
}

@Mapper
interface ChatExperimentMapper {
    @Update(
        """
        UPDATE chat_experiment
        SET enabled = #{enabled},
            rollout_percent = #{rolloutPercent},
            treatment_model = #{treatmentModel},
            prompt_version = #{promptVersion},
            parameters_json = #{parametersJson},
            updated_at = CURRENT_TIMESTAMP
        WHERE experiment_key = #{experimentKey}
        """,
    )
    fun updateConfig(
        @Param("enabled") enabled: Boolean,
        @Param("rolloutPercent") rolloutPercent: Int,
        @Param("treatmentModel") treatmentModel: String?,
        @Param("promptVersion") promptVersion: String?,
        @Param("parametersJson") parametersJson: String,
        @Param("experimentKey") experimentKey: String,
    ): Int

    @Insert(
        """
        INSERT INTO chat_experiment (
          experiment_key,
          enabled,
          rollout_percent,
          treatment_model,
          prompt_version,
          parameters_json
        ) VALUES (
          #{experimentKey},
          #{enabled},
          #{rolloutPercent},
          #{treatmentModel},
          #{promptVersion},
          #{parametersJson}
        )
        """,
    )
    fun insertConfig(
        @Param("experimentKey") experimentKey: String,
        @Param("enabled") enabled: Boolean,
        @Param("rolloutPercent") rolloutPercent: Int,
        @Param("treatmentModel") treatmentModel: String?,
        @Param("promptVersion") promptVersion: String?,
        @Param("parametersJson") parametersJson: String,
    ): Int

    @Select(
        """
        SELECT experiment_key AS experimentKey,
               enabled,
               rollout_percent AS rolloutPercent,
               treatment_model AS treatmentModel,
               prompt_version AS promptVersion,
               parameters_json AS parametersJson,
               updated_at AS updatedAt
        FROM chat_experiment
        WHERE experiment_key = #{experimentKey}
        LIMIT 1
        """,
    )
    fun findConfig(@Param("experimentKey") experimentKey: String): ChatExperimentRow?
}
