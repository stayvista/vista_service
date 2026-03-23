package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import kotlin.math.sqrt

@Service
class LocalRagRetriever(
    private val mapper: LocalRagRetrieverMapper,
    private val embedClient: EmbedClient,
    private val modelRegistry: LlmModelRegistry,
    private val chatCurationService: ChatCurationService,
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
        val poiCategoryFilter = resolvePoiCategoryFilter(query, slots, sourceTypeFilter)
        val candidates = loadIndexedDocuments(
            city = slots.city,
            sourceTypes = sourceTypeFilter,
            poiCategories = poiCategoryFilter,
            maxDocs = candidateLimit.coerceIn(50, 1000),
        )
        val curation = chatCurationService.activeRules()
        val filteredCandidates = candidates.filterNot { it.docId in curation.blacklistedDocIds }
        val removedByBlacklist = (candidates.size - filteredCandidates.size).coerceAtLeast(0)
        if (removedByBlacklist > 0) {
            meterRegistry.counter("chat_curation_applied_total", "type", "blacklist")
                .increment(removedByBlacklist.toDouble())
        }

        if (filteredCandidates.isEmpty()) {
            meterRegistry.counter("chat_rag_index_empty_total").increment()
            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
            meterRegistry.timer("chat_rag_ms").record(Duration.ofMillis(elapsed))
            return RagSearchResult(
                hits = emptyList(),
                retrievalMs = elapsed,
                usedEmbedding = false,
                sourceTypes = sourceTypeFilter,
                requestedPoiCategories = poiCategoryFilter,
                filteredCandidateCount = 0,
            )
        }

        val lexicalOrder = lexicalRank(filteredCandidates, query)
        var vectorOrder = emptyList<String>()
        var usedEmbedding = false

        runCatching {
            val queryVector = embedClient.embed(buildVectorQuery(query, slots))
            if (queryVector.isNotEmpty()) {
                val vectorByDoc = loadDocumentVectors(filteredCandidates.map { it.docId }, modelRegistry.embedModel())
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

        val updatedAtByDoc = filteredCandidates.associate { it.docId to it.updatedAt }
        val fused = HybridRanker.fuse(
            vectorOrder = vectorOrder,
            lexicalOrder = lexicalOrder,
            updatedAtByDoc = updatedAtByDoc,
            now = Instant.now(),
            rrfK = rrfK.coerceAtLeast(1),
            decayWindowDays = decayWindowDays.coerceAtLeast(1.0),
        )
        val boosted = fused
            .map { ranked ->
                val boostWeight = curation.topPickWeights[ranked.docId] ?: return@map ranked
                val boost = boostWeight.coerceIn(1, 500) / 500.0
                ranked.copy(score = ranked.score + boost)
            }
            .sortedByDescending { it.score }
        val boostedCount = boosted.count { it.docId in curation.topPickWeights }
        if (boostedCount > 0) {
            meterRegistry.counter("chat_curation_applied_total", "type", "top_pick")
                .increment(boostedCount.toDouble())
        }

        val candidateById = filteredCandidates.associateBy { it.docId }
        val hits = boosted
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
            .ensureMinimum(filteredCandidates)

        val retrievalMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
        meterRegistry.timer("chat_rag_ms").record(Duration.ofMillis(retrievalMs))
        return RagSearchResult(
            hits = hits,
            retrievalMs = retrievalMs,
            usedEmbedding = usedEmbedding,
            sourceTypes = sourceTypeFilter,
            requestedPoiCategories = poiCategoryFilter,
            filteredCandidateCount = filteredCandidates.size,
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
        poiCategories: Set<String>,
        maxDocs: Int,
    ): List<IndexedDoc> {
        return mapper.listIndexedDocuments(
            city = city,
            sourceTypes = sourceTypes.toList(),
            poiCategories = poiCategories.toList(),
            limit = maxDocs,
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

        val vectorsByDoc = linkedMapOf<String, MutableList<List<Double>>>()
        mapper.listDocumentVectors(model = model, docIds = docIds)
            .forEach { row ->
                val blob = row.vectorBlob
                if (blob != null) {
                    val vector = runCatching {
                        objectMapper.readValue(blob, object : TypeReference<List<Double>>() {})
                    }.getOrNull()

                    if (!vector.isNullOrEmpty()) {
                        vectorsByDoc.computeIfAbsent(row.docId) { mutableListOf() }.add(vector)
                    }
                }
            }

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
        val ticketRequested = normalized.contains("티켓") ||
            normalized.contains("체험") ||
            normalized.contains("ticket") ||
            normalized.contains("입장권")
        val packageRequested = normalized.contains("패키지") || normalized.contains("package")
        val propertyRequested = normalized.contains("숙소") ||
            normalized.contains("hotel") ||
            normalized.contains("property")
        val poiRequested = normalized.contains("맛집") ||
            normalized.contains("음식") ||
            normalized.contains("식당") ||
            normalized.contains("food") ||
            normalized.contains("restaurant") ||
            normalized.contains("쇼핑") ||
            normalized.contains("shopping") ||
            normalized.contains("팝업") ||
            normalized.contains("전시") ||
            normalized.contains("museum") ||
            normalized.contains("주변") ||
            normalized.contains("관광") ||
            normalized.contains("명소") ||
            normalized.contains("attraction") ||
            normalized.contains("poi")

        if (ticketRequested) {
            return setOf("TICKET", "PACKAGE")
        }
        if (packageRequested) {
            return setOf("PACKAGE")
        }
        if (propertyRequested) {
            return setOf("PROPERTY", "PACKAGE")
        }
        if (poiRequested) {
            return setOf("POI")
        }

        return when {
            slots.intent == "FOOD" ||
                slots.intent == "CULTURE" ||
                slots.intent == "SHOPPING" ||
                slots.intent == "ATTRACTION" -> setOf("POI")
            else -> emptySet()
        }
    }

    private fun resolvePoiCategoryFilter(query: String, slots: ChatSlots, sourceTypes: Set<String>): Set<String> {
        if (sourceTypes.isNotEmpty() && "POI" !in sourceTypes) {
            return emptySet()
        }

        val normalized = query.lowercase()
        return when {
            slots.intent == "FOOD" ||
                normalized.contains("맛집") ||
                normalized.contains("음식") ||
                normalized.contains("식당") ||
                normalized.contains("food") ||
                normalized.contains("restaurant") -> setOf("food")
            slots.intent == "CULTURE" || normalized.contains("전시") || normalized.contains("museum") -> setOf("museum")
            slots.intent == "SHOPPING" || normalized.contains("쇼핑") || normalized.contains("shopping") || normalized.contains("팝업") -> setOf("shopping")
            slots.intent == "ATTRACTION" || normalized.contains("관광") || normalized.contains("명소") || normalized.contains("attraction") -> setOf("attraction")
            else -> emptySet()
        }
    }

    private fun List<RagHit>.ensureMinimum(candidates: List<IndexedDoc>): List<RagHit> {
        if (this.size >= 2) {
            return this
        }
        val seeded = this.toMutableList()
        val existingDocIds = seeded.map { it.document.docId }.toMutableSet()
        candidates.asSequence()
            .filter { doc -> doc.docId !in existingDocIds }
            .take((2 - seeded.size).coerceAtLeast(0))
            .forEach { doc ->
                seeded += RagHit(document = doc.toRagDocument(), score = 0.0)
                existingDocIds += doc.docId
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

}

data class IndexedDoc(
    val docId: String,
    val sourceType: String,
    val refId: Long?,
    val city: String?,
    val title: String,
    val body: String,
    val updatedAt: Instant?,
)

data class RagVectorBlobRow(
    val docId: String,
    val vectorBlob: ByteArray?,
)

@Mapper
interface LocalRagRetrieverMapper {
    @Select(
        """
        <script>
        SELECT td.doc_id AS docId,
               COALESCE(td.source_type, 'POI') AS sourceType,
               td.ref_id AS refId,
               td.city,
               td.title,
               COALESCE(td.body, '') AS body,
               COALESCE(td.source_updated_at, td.updated_at) AS updatedAt
        FROM travel_doc td
        WHERE 1=1
          <if test="city != null and city != ''">
            AND td.city = #{city}
          </if>
          <if test="sourceTypes != null and sourceTypes.size() > 0">
            AND td.source_type IN
            <foreach collection="sourceTypes" item="sourceType" open="(" separator="," close=")">
              #{sourceType}
            </foreach>
          </if>
          <if test="poiCategories != null and poiCategories.size() > 0">
            AND (
              td.source_type &lt;&gt; 'POI'
              OR EXISTS (
                SELECT 1 FROM poi p
                WHERE p.id = td.ref_id
                  AND LOWER(COALESCE(p.category, '')) IN
                  <foreach collection="poiCategories" item="poiCategory" open="(" separator="," close=")">
                    #{poiCategory}
                  </foreach>
              )
            )
          </if>
        ORDER BY td.source_updated_at DESC, td.updated_at DESC
        LIMIT #{limit}
        </script>
        """,
    )
    fun listIndexedDocuments(
        @Param("city") city: String?,
        @Param("sourceTypes") sourceTypes: List<String>,
        @Param("poiCategories") poiCategories: List<String>,
        @Param("limit") limit: Int,
    ): List<IndexedDoc>

    @Select(
        """
        <script>
        SELECT c.doc_id AS docId, v.vector_blob AS vectorBlob
        FROM travel_doc_chunk c
        JOIN travel_doc_vec v ON v.chunk_id = c.chunk_id
        WHERE v.model = #{model}
          AND c.doc_id IN
          <foreach collection="docIds" item="docId" open="(" separator="," close=")">
            #{docId}
          </foreach>
        </script>
        """,
    )
    fun listDocumentVectors(
        @Param("model") model: String,
        @Param("docIds") docIds: List<String>,
    ): List<RagVectorBlobRow>
}
