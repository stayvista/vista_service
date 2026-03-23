package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.stereotype.Service

@Service
class ChatCurationService(
    private val mapper: ChatCurationMapper,
    private val meterRegistry: MeterRegistry,
) {
    fun list(): List<ChatCurationRule> {
        return mapper.listRules().map { it.toData() }
    }

    fun upsert(request: ChatCurationUpsertRequest): ChatCurationRule {
        val normalizedType = request.rule_type.trim().uppercase()
        require(normalizedType in setOf("BLACKLIST", "TOP_PICK")) {
            "rule_type must be BLACKLIST or TOP_PICK"
        }

        val updated = mapper.updateByDocIdAndType(
            weight = request.weight.coerceIn(1, 500),
            enabled = request.enabled,
            docId = request.doc_id.trim(),
            ruleType = normalizedType,
        )
        if (updated == 0) {
            mapper.insertRule(
                docId = request.doc_id.trim(),
                ruleType = normalizedType,
                weight = request.weight.coerceIn(1, 500),
                enabled = request.enabled,
            )
        }
        meterRegistry.counter("chat_curation_rule_total", "action", "upsert", "type", normalizedType).increment()
        return mapper.findByDocIdAndType(
            docId = request.doc_id.trim(),
            ruleType = normalizedType,
        )?.toData() ?: error("curation rule upsert failed")
    }

    fun update(ruleId: Long, request: ChatCurationUpdateRequest): ChatCurationRule {
        val affected = mapper.updateById(
            ruleId = ruleId,
            weight = request.weight.coerceIn(1, 500),
            enabled = request.enabled,
        )
        require(affected == 1) { "curation rule not found: $ruleId" }
        meterRegistry.counter("chat_curation_rule_total", "action", "update").increment()
        return mapper.findById(ruleId)?.toData() ?: error("curation rule update failed")
    }

    fun delete(ruleId: Long) {
        mapper.deleteById(ruleId)
        meterRegistry.counter("chat_curation_rule_total", "action", "delete").increment()
    }

    fun activeRules(): ChatCurationLookup {
        val rows = mapper.listActiveRules()

        val blacklist = mutableSetOf<String>()
        val topPicks = mutableMapOf<String, Int>()
        rows.forEach { row ->
            when (row.ruleType.uppercase()) {
                "BLACKLIST" -> blacklist += row.docId
                "TOP_PICK" -> topPicks[row.docId] = row.weight
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

data class ChatCurationRuleRow(
    val ruleId: Long,
    val docId: String,
    val ruleType: String,
    val weight: Int,
    val enabled: Boolean,
    val updatedAt: java.sql.Timestamp?,
) {
    fun toData(): ChatCurationRule = ChatCurationRule(
        rule_id = ruleId,
        doc_id = docId,
        rule_type = ruleType,
        weight = weight,
        enabled = enabled,
        updated_at = updatedAt?.toInstant()?.toString().orEmpty(),
    )
}

data class ChatCurationActiveRuleRow(
    val docId: String,
    val ruleType: String,
    val weight: Int,
)

@Mapper
interface ChatCurationMapper {
    @Select(
        """
        SELECT rule_id AS ruleId,
               doc_id AS docId,
               rule_type AS ruleType,
               weight,
               enabled,
               updated_at AS updatedAt
        FROM chat_curation_rule
        ORDER BY updated_at DESC
        """,
    )
    fun listRules(): List<ChatCurationRuleRow>

    @Update(
        """
        UPDATE chat_curation_rule
        SET weight = #{weight}, enabled = #{enabled}, updated_at = CURRENT_TIMESTAMP
        WHERE doc_id = #{docId} AND rule_type = #{ruleType}
        """,
    )
    fun updateByDocIdAndType(
        @Param("weight") weight: Int,
        @Param("enabled") enabled: Boolean,
        @Param("docId") docId: String,
        @Param("ruleType") ruleType: String,
    ): Int

    @Insert(
        """
        INSERT INTO chat_curation_rule (doc_id, rule_type, weight, enabled)
        VALUES (#{docId}, #{ruleType}, #{weight}, #{enabled})
        """,
    )
    fun insertRule(
        @Param("docId") docId: String,
        @Param("ruleType") ruleType: String,
        @Param("weight") weight: Int,
        @Param("enabled") enabled: Boolean,
    ): Int

    @Update(
        """
        UPDATE chat_curation_rule
        SET weight = #{weight}, enabled = #{enabled}, updated_at = CURRENT_TIMESTAMP
        WHERE rule_id = #{ruleId}
        """,
    )
    fun updateById(
        @Param("ruleId") ruleId: Long,
        @Param("weight") weight: Int,
        @Param("enabled") enabled: Boolean,
    ): Int

    @Delete("DELETE FROM chat_curation_rule WHERE rule_id = #{ruleId}")
    fun deleteById(@Param("ruleId") ruleId: Long): Int

    @Select(
        """
        SELECT rule_id AS ruleId,
               doc_id AS docId,
               rule_type AS ruleType,
               weight,
               enabled,
               updated_at AS updatedAt
        FROM chat_curation_rule
        WHERE rule_id = #{ruleId}
        LIMIT 1
        """,
    )
    fun findById(@Param("ruleId") ruleId: Long): ChatCurationRuleRow?

    @Select(
        """
        SELECT rule_id AS ruleId,
               doc_id AS docId,
               rule_type AS ruleType,
               weight,
               enabled,
               updated_at AS updatedAt
        FROM chat_curation_rule
        WHERE doc_id = #{docId} AND rule_type = #{ruleType}
        LIMIT 1
        """,
    )
    fun findByDocIdAndType(
        @Param("docId") docId: String,
        @Param("ruleType") ruleType: String,
    ): ChatCurationRuleRow?

    @Select(
        """
        SELECT doc_id AS docId, rule_type AS ruleType, weight
        FROM chat_curation_rule
        WHERE enabled = 1
        """,
    )
    fun listActiveRules(): List<ChatCurationActiveRuleRow>
}
