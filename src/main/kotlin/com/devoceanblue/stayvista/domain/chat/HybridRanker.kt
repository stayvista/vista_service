package com.devoceanblue.stayvista.domain.chat

import java.time.Duration
import java.time.Instant
import kotlin.math.exp

data class HybridRankedDoc(
    val docId: String,
    val score: Double,
)

object HybridRanker {
    fun fuse(
        vectorOrder: List<String>,
        lexicalOrder: List<String>,
        updatedAtByDoc: Map<String, Instant?>,
        now: Instant,
        rrfK: Int = 60,
        decayWindowDays: Double = 30.0,
    ): List<HybridRankedDoc> {
        val vectorRank = vectorOrder.withIndex().associate { it.value to (it.index + 1) }
        val lexicalRank = lexicalOrder.withIndex().associate { it.value to (it.index + 1) }

        val candidates = LinkedHashSet<String>()
        candidates.addAll(vectorOrder)
        candidates.addAll(lexicalOrder)

        if (candidates.isEmpty()) {
            return emptyList()
        }

        return candidates.map { docId ->
            val vRank = vectorRank[docId]
            val lRank = lexicalRank[docId]

            val vectorScore = if (vRank != null) 1.0 / (rrfK + vRank) else 0.0
            val lexicalScore = if (lRank != null) 1.0 / (rrfK + lRank) else 0.0
            val rrfScore = vectorScore + lexicalScore

            val updatedAt = updatedAtByDoc[docId]
            val decay = if (updatedAt == null) {
                1.0
            } else {
                val ageDays = Duration.between(updatedAt, now).toMillis().coerceAtLeast(0).toDouble() / 86_400_000.0
                exp(-ageDays / decayWindowDays).coerceIn(0.35, 1.0)
            }

            HybridRankedDoc(
                docId = docId,
                score = rrfScore * decay,
            )
        }.sortedByDescending { it.score }
    }
}
