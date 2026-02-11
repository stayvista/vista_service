package com.devoceanblue.stayvista.domain.chat

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HybridRankerTest {
    @Test
    fun `fuse should return stable order when lexical and vector overlap`() {
        val now = Instant.parse("2026-02-11T12:00:00Z")
        val ranked = HybridRanker.fuse(
            vectorOrder = listOf("doc-1", "doc-2", "doc-3"),
            lexicalOrder = listOf("doc-2", "doc-1", "doc-4"),
            updatedAtByDoc = mapOf(
                "doc-1" to now,
                "doc-2" to now,
                "doc-3" to now,
                "doc-4" to now,
            ),
            now = now,
            rrfK = 20,
            decayWindowDays = 30.0,
        )

        assertTrue(ranked.isNotEmpty())
        val topTwo = ranked.take(2).map { it.docId }.toSet()
        assertEquals(setOf("doc-1", "doc-2"), topTwo)
    }

    @Test
    fun `fuse should apply time decay to stale documents`() {
        val now = Instant.parse("2026-02-11T12:00:00Z")
        val old = Instant.parse("2025-01-01T12:00:00Z")

        val ranked = HybridRanker.fuse(
            vectorOrder = listOf("new-doc", "old-doc"),
            lexicalOrder = listOf("new-doc", "old-doc"),
            updatedAtByDoc = mapOf(
                "new-doc" to now,
                "old-doc" to old,
            ),
            now = now,
            rrfK = 20,
            decayWindowDays = 30.0,
        )

        assertEquals("new-doc", ranked.first().docId)
        assertTrue(ranked.first().score > ranked.last().score)
    }
}
