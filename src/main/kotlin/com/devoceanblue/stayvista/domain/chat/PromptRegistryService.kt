package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PromptRegistryService(
    private val mapper: PromptRegistryMapper,
    private val meterRegistry: MeterRegistry,
) {
    fun list(promptKey: String?): List<PromptTemplateRecord> {
        val rows = if (promptKey.isNullOrBlank()) mapper.listAll() else mapper.listByKey(promptKey)
        return rows.map { it.toData() }
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
            mapper.deactivateByKey(request.prompt_key)
        }

        val updated = mapper.updateTemplate(
            systemPrompt = request.system_prompt,
            userPromptTemplate = request.user_prompt_template,
            isActive = request.activate,
            promptKey = request.prompt_key,
            version = request.version,
        )
        if (updated == 0) {
            mapper.insertTemplate(
                promptKey = request.prompt_key,
                version = request.version,
                systemPrompt = request.system_prompt,
                userPromptTemplate = request.user_prompt_template,
                isActive = request.activate,
            )
        }
        meterRegistry.counter("chat_prompt_registry_total", "action", "upsert").increment()
        return getByKeyVersion(request.prompt_key, request.version) ?: throw IllegalStateException("prompt upsert failed")
    }

    @Transactional
    fun rollback(promptKey: String, version: String): PromptTemplateRecord {
        mapper.deactivateByKey(promptKey)
        val activated = mapper.activateByKeyVersion(promptKey = promptKey, version = version)
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

        return mapper.findActiveByKey("chat-core")?.toData()
    }

    private fun getByKeyVersion(promptKey: String, version: String): PromptTemplateRecord? {
        return mapper.findByKeyVersion(promptKey = promptKey, version = version)?.toData()
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

data class PromptTemplateRow(
    val promptKey: String,
    val version: String,
    val systemPrompt: String?,
    val userPromptTemplate: String?,
    val isActive: Boolean,
    val updatedAt: java.sql.Timestamp?,
) {
    fun toData(): PromptTemplateRecord = PromptTemplateRecord(
        prompt_key = promptKey,
        version = version,
        system_prompt = systemPrompt,
        user_prompt_template = userPromptTemplate,
        is_active = isActive,
        updated_at = updatedAt?.toInstant()?.toString().orEmpty(),
    )
}

@Mapper
interface PromptRegistryMapper {
    @Select(
        """
        SELECT prompt_key AS promptKey,
               version,
               system_prompt AS systemPrompt,
               user_prompt_template AS userPromptTemplate,
               is_active AS isActive,
               updated_at AS updatedAt
        FROM chat_prompt_template
        ORDER BY prompt_key ASC, updated_at DESC
        """,
    )
    fun listAll(): List<PromptTemplateRow>

    @Select(
        """
        SELECT prompt_key AS promptKey,
               version,
               system_prompt AS systemPrompt,
               user_prompt_template AS userPromptTemplate,
               is_active AS isActive,
               updated_at AS updatedAt
        FROM chat_prompt_template
        WHERE prompt_key = #{promptKey}
        ORDER BY prompt_key ASC, updated_at DESC
        """,
    )
    fun listByKey(@Param("promptKey") promptKey: String): List<PromptTemplateRow>

    @Update("UPDATE chat_prompt_template SET is_active = 0 WHERE prompt_key = #{promptKey}")
    fun deactivateByKey(@Param("promptKey") promptKey: String): Int

    @Update(
        """
        UPDATE chat_prompt_template
        SET system_prompt = #{systemPrompt},
            user_prompt_template = #{userPromptTemplate},
            is_active = #{isActive},
            updated_at = CURRENT_TIMESTAMP
        WHERE prompt_key = #{promptKey} AND version = #{version}
        """,
    )
    fun updateTemplate(
        @Param("systemPrompt") systemPrompt: String?,
        @Param("userPromptTemplate") userPromptTemplate: String?,
        @Param("isActive") isActive: Boolean,
        @Param("promptKey") promptKey: String,
        @Param("version") version: String,
    ): Int

    @Insert(
        """
        INSERT INTO chat_prompt_template (
          prompt_key,
          version,
          system_prompt,
          user_prompt_template,
          is_active
        ) VALUES (
          #{promptKey},
          #{version},
          #{systemPrompt},
          #{userPromptTemplate},
          #{isActive}
        )
        """,
    )
    fun insertTemplate(
        @Param("promptKey") promptKey: String,
        @Param("version") version: String,
        @Param("systemPrompt") systemPrompt: String?,
        @Param("userPromptTemplate") userPromptTemplate: String?,
        @Param("isActive") isActive: Boolean,
    ): Int

    @Update(
        "UPDATE chat_prompt_template SET is_active = 1 WHERE prompt_key = #{promptKey} AND version = #{version}",
    )
    fun activateByKeyVersion(
        @Param("promptKey") promptKey: String,
        @Param("version") version: String,
    ): Int

    @Select(
        """
        SELECT prompt_key AS promptKey,
               version,
               system_prompt AS systemPrompt,
               user_prompt_template AS userPromptTemplate,
               is_active AS isActive,
               updated_at AS updatedAt
        FROM chat_prompt_template
        WHERE prompt_key = #{promptKey} AND is_active = 1
        ORDER BY updated_at DESC
        LIMIT 1
        """,
    )
    fun findActiveByKey(@Param("promptKey") promptKey: String): PromptTemplateRow?

    @Select(
        """
        SELECT prompt_key AS promptKey,
               version,
               system_prompt AS systemPrompt,
               user_prompt_template AS userPromptTemplate,
               is_active AS isActive,
               updated_at AS updatedAt
        FROM chat_prompt_template
        WHERE prompt_key = #{promptKey} AND version = #{version}
        LIMIT 1
        """,
    )
    fun findByKeyVersion(
        @Param("promptKey") promptKey: String,
        @Param("version") version: String,
    ): PromptTemplateRow?
}
