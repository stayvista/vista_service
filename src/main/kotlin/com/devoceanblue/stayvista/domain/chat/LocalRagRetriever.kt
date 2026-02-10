package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import kotlin.math.sqrt

@Service
class LocalRagRetriever(
    private val jdbcTemplate: JdbcTemplate,
    private val embedClient: EmbedClient,
    private val vectorStore: InMemoryVectorStore,
    private val meterRegistry: MeterRegistry,
) {
    fun searchItems(query: String, slots: ChatSlots, limit: Int = 6): RagSearchResult {
        val startedAt = System.nanoTime()
        val docs = loadDocuments(slots.city)
        if (docs.isEmpty()) {
            return RagSearchResult(emptyList(), retrievalMs = 0, usedEmbedding = false)
        }

        val topK = limit.coerceIn(2, 20)
        val candidateQuery = buildString {
            append(query.trim())
            slots.city?.let { append(" city=$it") }
            slots.intent.ifBlank { "" }.let { append(" intent=$it") }
        }

        val result = try {
            val queryVector = embedClient.embed(candidateQuery)
            if (queryVector.isEmpty()) {
                lexicalSearch(docs, query, topK)
            } else {
                val scored = docs.map { doc ->
                    val docVector = resolveDocumentVector(doc)
                    RagHit(
                        document = doc,
                        score = cosineSimilarity(queryVector, docVector),
                    )
                }
                    .sortedByDescending { it.score }
                    .take(topK)
                    .ensureMinimum(docs)
                RagSearchResult(
                    hits = scored,
                    retrievalMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1),
                    usedEmbedding = true,
                )
            }
        } catch (_: Exception) {
            meterRegistry.counter("chat_rag_errors_total").increment()
            lexicalSearch(docs, query, topK)
        }

        meterRegistry.timer("chat_rag_ms").record(Duration.ofMillis(result.retrievalMs.coerceAtLeast(1)))
        return result
    }

    private fun lexicalSearch(docs: List<RagDocument>, query: String, topK: Int): RagSearchResult {
        val startedAt = System.nanoTime()
        val tokens = query.lowercase()
            .split(Regex("[^a-z0-9가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()

        val scored = docs.map { doc ->
            val haystack = "${doc.title} ${doc.snippet}".lowercase()
            val overlap = tokens.count { token -> haystack.contains(token) }
            RagHit(doc, overlap.toDouble())
        }
            .sortedByDescending { it.score }
            .take(topK)
            .ensureMinimum(docs)

        return RagSearchResult(
            hits = scored,
            retrievalMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1),
            usedEmbedding = false,
        )
    }

    private fun resolveDocumentVector(doc: RagDocument): List<Double> {
        val cacheKey = "vec:${doc.docId}"
        vectorStore.get(cacheKey)?.let { return it }
        val vector = embedClient.embed("${doc.title}\n${doc.snippet}")
        if (vector.isNotEmpty()) {
            vectorStore.put(cacheKey, vector)
        }
        return vector
    }

    private fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until size) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    private fun loadDocuments(city: String?): List<RagDocument> {
        val docs = mutableListOf<RagDocument>()

        docs += jdbcTemplate.query(
            """
            SELECT p.id, p.name, p.city, COALESCE(p.rating, 0) AS rating, COALESCE(MIN(rt.base_price), 0) AS price_min
            FROM property p
            LEFT JOIN room_type rt ON rt.property_id = p.id AND rt.status='ACTIVE'
            WHERE p.status='ACTIVE'
              AND (? IS NULL OR p.city = ?)
            GROUP BY p.id, p.name, p.city, p.rating
            ORDER BY p.rating DESC, p.id DESC
            LIMIT 16
            """.trimIndent(),
            { rs, _ ->
                val propertyId = rs.getLong("id")
                val cityName = rs.getString("city") ?: "Unknown"
                val price = rs.getLong("price_min")
                val rating = rs.getBigDecimal("rating")?.toDouble() ?: 0.0
                RagDocument(
                    docId = "property:$propertyId",
                    title = rs.getString("name"),
                    snippet = "$cityName · 평점 ${"%.1f".format(rating)} · 최저 ${price.toString().padStart(1, '0')} KRW",
                    sourceType = "PROPERTY",
                    metadata = mapOf("property_id" to propertyId),
                )
            },
            city,
            city,
        )

        docs += jdbcTemplate.query(
            """
            SELECT id, name, city, product_type
            FROM product
            WHERE status='ACTIVE'
              AND (? IS NULL OR city = ?)
            ORDER BY id DESC
            LIMIT 12
            """.trimIndent(),
            { rs, _ ->
                val productId = rs.getLong("id")
                val cityName = rs.getString("city") ?: "Unknown"
                val productType = rs.getString("product_type") ?: "TICKET"
                RagDocument(
                    docId = "ticket:$productId",
                    title = rs.getString("name"),
                    snippet = "$cityName · $productType · 실시간 잔여 수량 연동",
                    sourceType = "TICKET",
                    metadata = mapOf("product_id" to productId),
                )
            },
            city,
            city,
        )

        docs += jdbcTemplate.query(
            """
            SELECT id, name, amount_total, currency
            FROM package_product
            WHERE status='ACTIVE'
            ORDER BY id DESC
            LIMIT 8
            """.trimIndent(),
            { rs, _ ->
                val packageId = rs.getLong("id")
                val amount = rs.getLong("amount_total")
                val currency = rs.getString("currency") ?: "KRW"
                RagDocument(
                    docId = "package:$packageId",
                    title = rs.getString("name"),
                    snippet = "숙소+티켓 결합 패키지 · ${amount.toString()} $currency",
                    sourceType = "PACKAGE",
                    metadata = mapOf("package_id" to packageId),
                )
            },
        )

        docs += jdbcTemplate.query(
            """
            SELECT id, name, category, city
            FROM poi
            WHERE (? IS NULL OR city = ?)
            ORDER BY id DESC
            LIMIT 24
            """.trimIndent(),
            { rs, _ ->
                val poiId = rs.getLong("id")
                val cityName = rs.getString("city") ?: "Unknown"
                val category = rs.getString("category") ?: "spot"
                RagDocument(
                    docId = "poi:$poiId",
                    title = rs.getString("name"),
                    snippet = "$cityName · $category 추천 장소",
                    sourceType = "POI",
                    metadata = mapOf("poi_id" to "poi_$poiId"),
                )
            },
            city,
            city,
        )

        return docs
    }

    private fun List<RagHit>.ensureMinimum(allDocs: List<RagDocument>): List<RagHit> {
        if (this.size >= 2) {
            return this
        }
        val seeded = this.toMutableList()
        allDocs.take(2 - this.size).forEach { doc ->
            seeded += RagHit(document = doc, score = 0.0)
        }
        return seeded
    }
}
