package com.devoceanblue.stayvista.domain.chat

import org.springframework.stereotype.Component

@Component
class ChatPromptFactory(
    private val safetyPolicy: ChatSafetyPolicy,
    private val promptRegistryService: PromptRegistryService,
) {
    fun buildSystemPrompt(promptVersion: String? = null): String {
        val registryPrompt = promptRegistryService.resolveSystemPrompt(promptVersion)
        if (!registryPrompt.isNullOrBlank()) {
            return registryPrompt.trim()
        }

        return """
            You are StayVista AI concierge.
            You must answer in JSON only.
            Never include markdown or extra commentary.
            
            Output schema:
            {
              "assistant_text": string,
              "cards": [
                {
                  "type": "PROPERTY" | "TICKET" | "PACKAGE" | "POI",
                  "id": string,
                  "title": string,
                  "price": string | null,
                  "why": string,
                  "sources": [
                    {
                      "doc_id": string,
                      "title": string,
                      "url": string | null,
                      "snippet": string,
                      "source_type": string
                    }
                  ]
                }
              ],
              "followups": [string],
              "context_used": object,
              "llm_used": true
            }
            
            Rules:
            - sources must include at least one entry for each card.
            - keep followups up to 2 items.
            - if evidence is weak, reduce cards and explain constraints.
        """.trimIndent()
    }

    fun buildUserPrompt(
        request: ChatRecommendRequest,
        slots: ChatSlots,
        hits: List<RagHit>,
        memory: ChatMemorySnapshot = ChatMemorySnapshot(),
        promptVersion: String? = null,
    ): String {
        val registryTemplate = promptRegistryService.resolveUserPromptTemplate(promptVersion)?.trim().orEmpty()
        val evidence = hits.joinToString("\n") { hit ->
            val doc = hit.document
            val safeTitle = safetyPolicy.sanitizeEvidenceText(doc.title)
            val safeSnippet = safetyPolicy.sanitizeEvidenceText(doc.snippet)
            "- ${doc.docId} | ${doc.sourceType} | $safeTitle | $safeSnippet | score=${"%.4f".format(hit.score)}"
        }

        return """
            ${if (registryTemplate.isNotBlank()) "PROMPT_TEMPLATE:\n$registryTemplate\n" else ""}
            USER_QUERY:
            ${request.message.trim()}

            CONTEXT:
            city=${slots.city ?: "unknown"}
            days=${slots.days ?: "unknown"}
            budget_krw=${slots.budgetKrw ?: "unknown"}
            companions=${slots.companions ?: "unknown"}
            intent=${slots.intent}
            memory_state=${memory.state}
            memory_turn_count=${memory.turnCount}
            memory_summary=${memory.runningSummary.replace('\n', ' ').takeLast(320).ifBlank { "none" }}

            RETRIEVED_EVIDENCE:
            $evidence

            Return valid JSON only. Include at least 2 source documents across cards.
        """.trimIndent()
    }

    fun buildRepairPrompt(rawOutput: String): String {
        return """
            Convert the following text into valid JSON that strictly matches the schema.
            Do not add markdown.
            Keep factual details from the original text.
            Use "sources" for card citations.

            TEXT:
            $rawOutput
        """.trimIndent()
    }
}
