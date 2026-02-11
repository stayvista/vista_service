package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import kotlin.math.sqrt

@Service
class LocalRagRetriever(
    private val jdbcTemplate: JdbcTemplate,
    private val embedClient: EmbedClient,
    private val modelRegistry: LlmModelRegistry,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    @Value("\${stayvista.chat.rag.candidate-limit:320}") private val candidateLimit: Int,
    @Value("\${stayvista.chat.rag.rrf-k:60}") private val rrfK: Int,
    @Value("\${stayvista.chat.rag.decay-window-days:30}") private val decayWindowDays: Double,
) {
    fun searchItems(query: String, slots: ChatSlots, limit: Int = 6): RagSearchResult {
        val startedAt = System.nanoTime()
        val topK = limit.coerceIn(2, 20)
        val sourceTypeFilter = resolveSourceTypeFilter(query, slots)
        val candidates = loadIndexedDocuments(
            city = slots.city,
            sourceTypes = sourceTypeFilter,
            maxDocs = candidateLimit.coerceIn(50, 1000),
        )

        if (candidates.isEmpty()) {
            meterRegistry.counter("chat_rag_index_empty_total").increment()
            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
            meterRegistry.timer("chat_rag_ms").record(Duration.ofMillis(elapsed))
            return RagSearchResult(emptyList(), retrievalMs = elapsed, usedEmbedding = false)
        }

        val lexicalOrder = lexicalRank(candidates, query)
        var vectorOrder = emptyList<String>()
        var usedEmbedding = false

        runCatching {
            val queryVector = embedClient.embed(buildVectorQuery(query, slots))
            if (queryVector.isNotEmpty()) {
                val vectorByDoc = loadDocumentVectors(candidates.map { it.docId }, modelRegistry.embedModel())
                if (vectorByDoc.isNotEmpty()) {
                    vectorOrder = vectorByDoc.entries
                        .map { (docId, vector) -> docId to cosineSimilarity(queryVector, vector) }
                        .sortedByDescending { it.second }
                        .map { it.first }
                        .take(candidateLimit)
                    usedEmbedding = true
                }
            }
        }.onFailure {
            meterRegistry.counter("chat_rag_errors_total").increment()
        }

        val updatedAtByDoc = candidates.associate { it.docId to it.updatedAt }
        val fused = HybridRanker.fuse(
            vectorOrder = vectorOrder,
            lexicalOrder = lexicalOrder,
            updatedAtByDoc = updatedAtByDoc,
            now = Instant.now(),
            rrfK = rrfK.coerceAtLeast(1),
            decayWindowDays = decayWindowDays.coerceAtLeast(1.0),
        )

        val candidateById = candidates.associateBy { it.docId }
        val hits = fused
            .take(topK)
            .mapNotNull { ranked ->
                val doc = candidateById[ranked.docId] ?: return@mapNotNull null
                RagHit(
                    document = doc.toRagDocument(),
                    score = ranked.score,
                )
            }
            .ifEmpty {
                lexicalOrder.take(topK).mapNotNull { docId ->
                    candidateById[docId]?.let { doc ->
                        RagHit(doc.toRagDocument(), score = 0.0)
                    }
                }
            }
            .ensureMinimum(candidates)

        val retrievalMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
        meterRegistry.timer("chat_rag_ms").record(Duration.ofMillis(retrievalMs))
        return RagSearchResult(
            hits = hits,
            retrievalMs = retrievalMs,
            usedEmbedding = usedEmbedding,
        )
    }

    private fun buildVectorQuery(query: String, slots: ChatSlots): String {
        return buildString {
            append(query.trim())
            slots.city?.let {
                append(" city=")
                append(it)
            }
            append(" intent=")
            append(slots.intent)
            if (slots.sourceTypes.isNotEmpty()) {
                append(" source_types=")
                append(slots.sourceTypes.joinToString(","))
            }
        }
    }

    private fun loadIndexedDocuments(
        city: String?,
        sourceTypes: Set<String>,
        maxDocs: Int,
    ): List<IndexedDoc> {
        val params = mutableListOf<Any>()
        val sql = StringBuilder(
            """
            SELECT doc_id, source_type, ref_id, city, title, body, source_updated_at, updated_at
            FROM travel_doc
            WHERE 1=1
            """.trimIndent(),
        )

        if (!city.isNullOrBlank()) {
            sql.append(" AND (city = ? OR city IS NULL)")
            params += city
        }

        if (sourceTypes.isNotEmpty()) {
            val placeholders = sourceTypes.joinToString(",") { "?" }
            sql.append(" AND source_type IN ($placeholders)")
            params.addAll(sourceTypes.toList())
        }

        sql.append(" ORDER BY source_updated_at DESC, updated_at DESC")
        sql.append(" LIMIT ?")
        params += maxDocs

        return jdbcTemplate.query(
            sql.toString(),
            { rs, _ ->
                val docId = rs.getString("doc_id")
                val sourceType = rs.getString("source_type") ?: "POI"
                val refId = rs.getLong("ref_id").takeIf { !rs.wasNull() }
                val docCity = rs.getString("city")
                val title = rs.getString("title")
                val body = rs.getString("body") ?: ""
                val sourceUpdated = rs.getTimestamp("source_updated_at")?.toInstant()
                    ?: rs.getTimestamp("updated_at")?.toInstant()

                IndexedDoc(
                    docId = docId,
                    sourceType = sourceType,
                    refId = refId,
                    city = docCity,
                    title = title,
                    body = body,
                    updatedAt = sourceUpdated,
                )
            },
            *params.toTypedArray(),
        )
    }

    private fun lexicalRank(candidates: List<IndexedDoc>, query: String): List<String> {
        val tokens = query.lowercase()
            .split(Regex("[^a-z0-9가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()

        if (tokens.isEmpty()) {
            return candidates.map { it.docId }
        }

        return candidates
            .map { doc ->
                val haystack = "${doc.title} ${doc.body}".lowercase()
                val overlap = tokens.count { token -> haystack.contains(token) }
                val phraseBoost = if (haystack.contains(query.trim().lowercase())) 2 else 0
                doc.docId to (overlap + phraseBoost)
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun loadDocumentVectors(docIds: List<String>, model: String): Map<String, List<Double>> {
        if (docIds.isEmpty()) return emptyMap()

        val placeholders = docIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT c.doc_id, v.vector_blob
            FROM travel_doc_chunk c
            JOIN travel_doc_vec v ON v.chunk_id = c.chunk_id
            WHERE v.model = ?
              AND c.doc_id IN ($placeholders)
            """.trimIndent()

        val vectorsByDoc = linkedMapOf<String, MutableList<List<Double>>>()
        jdbcTemplate.query(
            sql,
            { rs ->
                val docId = rs.getString("doc_id")
                val blob = rs.getBytes("vector_blob")
                if (blob != null) {
                    val vector = runCatching {
                        objectMapper.readValue(blob, object : TypeReference<List<Double>>() {})
                    }.getOrNull()

                    if (!vector.isNullOrEmpty()) {
                        vectorsByDoc.computeIfAbsent(docId) { mutableListOf() }.add(vector)
                    }
                }
            },
            model,
            *docIds.toTypedArray(),
        )

        return vectorsByDoc.mapValues { (_, vectors) -> averageVectors(vectors) }
    }

    private fun averageVectors(vectors: List<List<Double>>): List<Double> {
        if (vectors.isEmpty()) return emptyList()
        val size = vectors.minOfOrNull { it.size } ?: return emptyList()
        if (size == 0) return emptyList()

        val summed = DoubleArray(size)
        vectors.forEach { vector ->
            for (i in 0 until size) {
                summed[i] += vector[i]
            }
        }

        return summed.map { it / vectors.size.toDouble() }
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

    private fun resolveSourceTypeFilter(query: String, slots: ChatSlots): Set<String> {
        if (slots.sourceTypes.isNotEmpty()) {
            return slots.sourceTypes
        }

        val normalized = query.lowercase()
        return when {
            normalized.contains("패키지") || normalized.contains("package") -> setOf("PACKAGE")
            normalized.contains("티켓") || normalized.contains("체험") || normalized.contains("ticket") -> setOf("TICKET", "PACKAGE")
            normalized.contains("숙소") || normalized.contains("hotel") || normalized.contains("property") -> setOf("PROPERTY", "PACKAGE")
            normalized.contains("주변") || normalized.contains("관광") || normalized.contains("poi") -> setOf("POI")
            else -> emptySet()
        }
    }

    private fun List<RagHit>.ensureMinimum(candidates: List<IndexedDoc>): List<RagHit> {
        if (this.size >= 2) {
            return this
        }
        val seeded = this.toMutableList()
        candidates.take(2 - this.size).forEach { doc ->
            seeded += RagHit(document = doc.toRagDocument(), score = 0.0)
        }
        return seeded
    }

    private fun IndexedDoc.toRagDocument(): RagDocument {
        val snippetSource = body.lines().joinToString(" ").take(180)
        return RagDocument(
            docId = docId,
            title = title,
            snippet = snippetSource,
            sourceType = sourceType,
            metadata = buildMap {
                refId?.let { put("ref_id", it) }
                city?.let { put("city", it) }
            },
        )
    }

    private data class IndexedDoc(
        val docId: String,
        val sourceType: String,
        val refId: Long?,
        val city: String?,
        val title: String,
        val body: String,
        val updatedAt: Instant?,
    )
}
