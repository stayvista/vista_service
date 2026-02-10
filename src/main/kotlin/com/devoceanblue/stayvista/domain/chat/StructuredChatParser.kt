package com.devoceanblue.stayvista.domain.chat

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class StructuredOutputParseException(message: String) : RuntimeException(message)

@Component
class StructuredChatParser(
    private val objectMapper: ObjectMapper,
) {
    fun parseStrict(raw: String): StructuredLlmOutput {
        val root = try {
            objectMapper.readTree(raw)
        } catch (ex: Exception) {
            throw StructuredOutputParseException("invalid_json")
        }

        if (!root.isObject) {
            throw StructuredOutputParseException("root_must_be_object")
        }

        val assistantText = root.requiredText("assistant_text")
        val cardsNode = root.path("cards")
        if (!cardsNode.isArray) {
            throw StructuredOutputParseException("cards_must_be_array")
        }

        val cards = cardsNode.map { cardNode ->
            parseCard(cardNode)
        }

        val followupsNode = root.path("followups")
        if (!followupsNode.isArray) {
            throw StructuredOutputParseException("followups_must_be_array")
        }
        val followups = followupsNode
            .mapNotNull { node -> node.takeIf { it.isTextual }?.asText()?.trim() }
            .filter { it.isNotEmpty() }
            .take(2)

        val contextUsedNode = root.path("context_used")
        val contextUsed: Map<String, Any?> = if (contextUsedNode.isObject) {
            objectMapper.convertValue(contextUsedNode, Map::class.java) as Map<String, Any?>
        } else {
            emptyMap()
        }

        val llmUsedNode = root.path("llm_used")
        if (!llmUsedNode.isBoolean) {
            throw StructuredOutputParseException("llm_used_must_be_boolean")
        }

        return StructuredLlmOutput(
            assistantText = assistantText,
            cards = cards,
            followups = followups,
            contextUsed = contextUsed,
            llmUsed = llmUsedNode.asBoolean(),
        )
    }

    private fun parseCard(cardNode: JsonNode): StructuredCard {
        if (!cardNode.isObject) {
            throw StructuredOutputParseException("card_must_be_object")
        }

        val type = cardNode.requiredText("type").uppercase()
        val title = cardNode.requiredText("title")
        val sourceNode = cardNode.path("source")
        if (!sourceNode.isArray || sourceNode.isEmpty) {
            throw StructuredOutputParseException("card_source_required")
        }

        val source = sourceNode.map { sourceEntry ->
            if (!sourceEntry.isObject) {
                throw StructuredOutputParseException("source_entry_must_be_object")
            }
            ChatSource(
                doc_id = sourceEntry.requiredText("doc_id"),
                title = sourceEntry.requiredText("title"),
                snippet = sourceEntry.requiredText("snippet"),
                source_type = sourceEntry.requiredText("source_type"),
            )
        }

        return StructuredCard(
            type = type,
            id = cardNode.path("id").takeIf { it.isTextual }?.asText(),
            title = title,
            price = cardNode.path("price").takeIf { it.isTextual }?.asText(),
            why = cardNode.path("why").takeIf { it.isTextual }?.asText(),
            source = source,
        )
    }

    private fun JsonNode.requiredText(field: String): String {
        val value = path(field)
        if (!value.isTextual || value.asText().isBlank()) {
            throw StructuredOutputParseException("$field is required")
        }
        return value.asText().trim()
    }
}
