package com.devoceanblue.stayvista.domain.chat

import org.springframework.stereotype.Service

@Service
class ItineraryPlannerService {
    fun plan(
        cards: List<ChatCard>,
        fallbackHits: List<RagHit>,
        days: Int?,
    ): List<ItineraryItem> {
        val horizon = days?.coerceIn(1, 5) ?: 2
        if (cards.isEmpty() && fallbackHits.isEmpty()) {
            return emptyList()
        }

        val seedCards = if (cards.isNotEmpty()) {
            cards
        } else {
            fallbackHits.take(6).map { hit ->
                val source = listOf(hit.document.toSource())
                ChatCard(
                    type = hit.document.sourceType,
                    id = hit.document.docId,
                    title = hit.document.title,
                    why = hit.document.snippet,
                    source = source,
                )
            }
        }
        val groundedCards = seedCards.filter { it.source.isNotEmpty() || it.sources.isNotEmpty() }
        if (groundedCards.isEmpty()) {
            return emptyList()
        }

        val slots = listOf("MORNING", "AFTERNOON", "EVENING")
        val result = mutableListOf<ItineraryItem>()

        for (day in 1..horizon) {
            for (slotIndex in slots.indices) {
                val card = groundedCards[(day + slotIndex - 1) % groundedCards.size]
                val source = card.source.firstOrNull() ?: card.sources.firstOrNull() ?: continue
                result += ItineraryItem(
                    day = day,
                    time_slot = slots[slotIndex],
                    title = card.title,
                    item_type = card.type.uppercase(),
                    reason = card.why ?: "추천 근거 문서를 기반으로 배치된 일정입니다.",
                    source = source,
                )
            }
        }

        return result
    }

    private fun RagDocument.toSource(): ChatSource {
        return ChatSource(
            doc_id = docId,
            title = title,
            snippet = snippet,
            source_type = sourceType,
        )
    }
}

data class ItineraryItem(
    val day: Int,
    val time_slot: String,
    val title: String,
    val item_type: String,
    val reason: String,
    val source: ChatSource,
)
