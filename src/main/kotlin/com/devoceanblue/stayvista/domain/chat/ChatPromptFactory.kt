package com.devoceanblue.stayvista.domain.chat

import org.springframework.stereotype.Component

@Component
class ChatPromptFactory {
    fun buildSystemPrompt(): String {
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
                  "source": [
                    {
                      "doc_id": string,
                      "title": string,
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
            - source must include at least one entry for each card.
            - keep followups up to 2 items.
            - if evidence is weak, reduce cards and explain constraints.
        """.trimIndent()
    }

    fun buildUserPrompt(
        request: ChatRecommendRequest,
        slots: ChatSlots,
        hits: List<RagHit>,
    ): String {
        val evidence = hits.joinToString("\n") { hit ->
            val doc = hit.document
            "- ${doc.docId} | ${doc.sourceType} | ${doc.title} | ${doc.snippet} | score=${"%.4f".format(hit.score)}"
        }

        return """
            USER_QUERY:
            ${request.message.trim()}

            CONTEXT:
            city=${slots.city ?: "unknown"}
            days=${slots.days ?: "unknown"}
            budget_krw=${slots.budgetKrw ?: "unknown"}
            companions=${slots.companions ?: "unknown"}
            intent=${slots.intent}

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

            TEXT:
            $rawOutput
        """.trimIndent()
    }
}
