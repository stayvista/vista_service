package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class RagIndexBuilderService(
    private val mapper: RagIndexBuilderMapper,
    private val embedClient: EmbedClient,
    private val modelRegistry: LlmModelRegistry,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    @Value("\${stayvista.chat.rag.incremental-sync-enabled:true}") private val incrementalSyncEnabled: Boolean,
) {
    private val lastFullBuildMs = AtomicLong(0)

    @Scheduled(
        fixedDelayString = "\${stayvista.chat.rag.incremental-sync-ms:300000}",
        initialDelayString = "\${stayvista.chat.rag.incremental-sync-initial-delay-ms:30000}",
    )
    fun scheduledIncrementalBuild() {
        if (!incrementalSyncEnabled) {
            return
        }
        runCatching {
            rebuildIncremental(limit = 1000)
        }.onFailure {
            meterRegistry.counter("chat_rag_index_total", "mode", "incremental", "result", "failed").increment()
        }
    }

    fun rebuildAll(limit: Int?): RagIndexBuildData {
        return rebuild(mode = RagIndexBuildMode.FULL, limit = limit)
    }

    fun rebuildIncremental(limit: Int?): RagIndexBuildData {
        return rebuild(mode = RagIndexBuildMode.INCREMENTAL, limit = limit)
    }

    private fun rebuild(mode: RagIndexBuildMode, limit: Int?): RagIndexBuildData {
        val startedAt = System.nanoTime()
        val model = modelRegistry.embedModel()

        return runCatching {
            val sourceDocs = when (mode) {
                RagIndexBuildMode.FULL -> loadAllSourceDocs(limit)
                RagIndexBuildMode.INCREMENTAL -> loadIncrementalSourceDocs(limit)
            }

            val scanned = sourceDocs.size
            if (sourceDocs.isEmpty()) {
                val elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
                val empty = RagIndexBuildData(
                    mode = mode.name.lowercase(),
                    scanned = 0,
                    updated_docs = 0,
                    skipped_docs = 0,
                    upserted_chunks = 0,
                    upserted_vectors = 0,
                    removed_docs = 0,
                    elapsed_ms = elapsed,
                    speedup_vs_full = null,
                )
                meterRegistry.timer("chat_rag_index_ms", "mode", mode.name.lowercase()).record(Duration.ofMillis(elapsed))
                meterRegistry.counter("chat_rag_index_total", "mode", mode.name.lowercase(), "result", "success").increment()
                return@runCatching empty
            }

            val existingHashByDocId = loadExistingHashes(sourceDocs.map { it.docId })

            var updatedDocs = 0
            var skippedDocs = 0
            var upsertedChunks = 0
            var upsertedVectors = 0

            sourceDocs.forEach { doc ->
                val existingHash = existingHashByDocId[doc.docId]
                if (existingHash == doc.hash) {
                    skippedDocs += 1
                    return@forEach
                }

                upsertDocument(doc)
                updatedDocs += 1

                val chunkStats = upsertChunksAndVectors(doc, model)
                upsertedChunks += chunkStats.chunksUpserted
                upsertedVectors += chunkStats.vectorsUpserted
            }

            val removedDocs = if (mode == RagIndexBuildMode.FULL && limit == null) {
                deleteStaleDocuments(sourceDocs.map { it.docId }.toSet())
            } else {
                0
            }

            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1)
            if (mode == RagIndexBuildMode.FULL) {
                lastFullBuildMs.set(elapsed)
            }

            val speedup = if (mode == RagIndexBuildMode.INCREMENTAL) {
                val baseline = lastFullBuildMs.get().takeIf { it > 0 }?.toDouble()
                if (baseline != null) {
                    (baseline / elapsed.toDouble())
                } else {
                    null
                }
            } else {
                null
            }

            val result = RagIndexBuildData(
                mode = mode.name.lowercase(),
                scanned = scanned,
                updated_docs = updatedDocs,
                skipped_docs = skippedDocs,
                upserted_chunks = upsertedChunks,
                upserted_vectors = upsertedVectors,
                removed_docs = removedDocs,
                elapsed_ms = elapsed,
                speedup_vs_full = speedup,
            )

            meterRegistry.timer("chat_rag_index_ms", "mode", mode.name.lowercase()).record(Duration.ofMillis(elapsed))
            meterRegistry.counter("chat_rag_index_total", "mode", mode.name.lowercase(), "result", "success").increment()

            if (mode == RagIndexBuildMode.INCREMENTAL && speedup != null) {
                meterRegistry.gauge("chat_rag_incremental_speedup", speedup)
            }

            result
        }.getOrElse { ex ->
            meterRegistry.counter("chat_rag_index_total", "mode", mode.name.lowercase(), "result", "failed").increment()
            throw ex
        }
    }

    private fun loadAllSourceDocs(limit: Int?): List<SourceDoc> {
        val docs = mutableListOf<SourceDoc>()
        docs += loadPropertyDocs(limit = limit, updatedAfter = null)
        docs += loadTicketDocs(limit = limit, updatedAfter = null)
        docs += loadPackageDocs(limit = limit, updatedAfter = null)
        docs += loadPoiDocs(limit = limit, createdAfter = null)
        return docs
    }

    private fun loadIncrementalSourceDocs(limit: Int?): List<SourceDoc> {
        val docs = mutableListOf<SourceDoc>()
        docs += loadPropertyDocs(limit = limit, updatedAfter = latestIndexedAt("PROPERTY"))
        docs += loadTicketDocs(limit = limit, updatedAfter = latestIndexedAt("TICKET"))
        docs += loadPackageDocs(limit = limit, updatedAfter = latestIndexedAt("PACKAGE"))
        docs += loadPoiDocs(limit = limit, createdAfter = latestIndexedAt("POI"))
        return docs
    }

    private fun latestIndexedAt(sourceType: String): LocalDateTime? {
        return mapper.findLatestIndexedAt(sourceType)
    }

    private fun loadExistingHashes(docIds: List<String>): Map<String, String> {
        if (docIds.isEmpty()) return emptyMap()
        return mapper.listExistingHashes(docIds).associate { it.docId to it.docHash }
    }

    private fun loadPropertyDocs(limit: Int?, updatedAfter: LocalDateTime?): List<SourceDoc> {
        return mapper.listPropertyDocRows(updatedAfter = updatedAfter, limit = limit)
            .map { row ->
                val propertyId = row.id
                val city = row.city
                val country = row.country
                val rating = row.rating
                val minPrice = row.minPrice
                val updatedAt = row.updatedAt

                val title = row.name
                val body = buildString {
                    append("숙소 ")
                    append(title)
                    append('\n')
                    append("도시: ")
                    append(city ?: "Unknown")
                    append('\n')
                    append("국가: ")
                    append(country ?: "Unknown")
                    append('\n')
                    append("평점: ")
                    append("%.1f".format(rating))
                    append('\n')
                    append("최저가: ")
                    append(minPrice)
                    append(" KRW")
                }

                SourceDoc(
                    docId = "property:$propertyId",
                    sourceType = "PROPERTY",
                    refId = propertyId,
                    city = city,
                    title = title,
                    body = body,
                    sourceUpdatedAt = updatedAt,
                )
            }
    }

    private fun loadTicketDocs(limit: Int?, updatedAfter: LocalDateTime?): List<SourceDoc> {
        return mapper.listTicketDocRows(updatedAfter = updatedAfter, limit = limit)
            .map { row ->
                val productId = row.id
                val city = row.city
                val productType = row.productType ?: "TICKET"
                val nextStart = row.nextStart
                val remain = row.maxRemain
                val updatedAt = row.updatedAt

                val title = row.name
                val body = buildString {
                    append("티켓/체험 ")
                    append(title)
                    append('\n')
                    append("도시: ")
                    append(city ?: "Unknown")
                    append('\n')
                    append("유형: ")
                    append(productType)
                    append('\n')
                    append("가장 빠른 시작: ")
                    append(nextStart?.toString() ?: "미정")
                    append('\n')
                    append("최대 잔여: ")
                    append(remain)
                }

                SourceDoc(
                    docId = "ticket:$productId",
                    sourceType = "TICKET",
                    refId = productId,
                    city = city,
                    title = title,
                    body = body,
                    sourceUpdatedAt = updatedAt,
                )
            }
    }

    private fun loadPackageDocs(limit: Int?, updatedAfter: LocalDateTime?): List<SourceDoc> {
        return mapper.listPackageDocRows(updatedAfter = updatedAfter, limit = limit)
            .map { row ->
                val packageId = row.id
                val amount = row.amountTotal
                val currency = row.currency ?: "KRW"
                val updatedAt = row.updatedAt
                val componentCity = row.componentCity
                val componentCityCount = row.componentCityCount

                val title = row.name
                val city = inferCityFromText(title)
                    ?: componentCity.takeIf { !it.isNullOrBlank() && componentCityCount == 1 }
                val body = buildString {
                    append("패키지 ")
                    append(title)
                    append('\n')
                    append("도시: ")
                    append(city ?: "Unknown")
                    append('\n')
                    append("금액: ")
                    append(amount)
                    append(' ')
                    append(currency)
                    append('\n')
                    append("상태: ACTIVE")
                }

                SourceDoc(
                    docId = "package:$packageId",
                    sourceType = "PACKAGE",
                    refId = packageId,
                    city = city,
                    title = title,
                    body = body,
                    sourceUpdatedAt = updatedAt,
                )
            }
    }

    private fun inferCityFromText(text: String?): String? {
        val normalized = text?.lowercase()?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return when {
            normalized.contains("seoul") || normalized.contains("서울") -> "Seoul"
            normalized.contains("busan") || normalized.contains("부산") -> "Busan"
            normalized.contains("jeju") || normalized.contains("제주") -> "Jeju"
            normalized.contains("incheon") || normalized.contains("인천") -> "Incheon"
            else -> null
        }
    }

    private fun loadPoiDocs(limit: Int?, createdAfter: LocalDateTime?): List<SourceDoc> {
        return mapper.listPoiDocRows(createdAfter = createdAfter, limit = limit)
            .map { row ->
                val poiId = row.id
                val city = row.city
                val category = row.category ?: "spot"
                val createdAt = row.createdAt

                val title = row.name
                val body = buildString {
                    append("POI ")
                    append(title)
                    append('\n')
                    append("도시: ")
                    append(city ?: "Unknown")
                    append('\n')
                    append("카테고리: ")
                    append(category)
                }

                SourceDoc(
                    docId = "poi:$poiId",
                    sourceType = "POI",
                    refId = poiId,
                    city = city,
                    title = title,
                    body = body,
                    sourceUpdatedAt = createdAt,
                )
            }
    }

    private fun upsertDocument(doc: SourceDoc) {
        mapper.upsertDocument(
            docId = doc.docId,
            sourceType = doc.sourceType,
            refId = doc.refId,
            city = doc.city,
            title = doc.title,
            body = doc.body,
            docHash = doc.hash,
            sourceUpdatedAt = doc.sourceUpdatedAt,
        )
    }

    private fun upsertChunksAndVectors(doc: SourceDoc, model: String): ChunkUpsertStats {
        val existingHashesByChunkOrder = mapper.listChunkHashes(doc.docId).associate { it.chunkOrder to it.chunkHash }

        val chunks = chunkText(doc.body)
        var chunksUpserted = 0
        var vectorsUpserted = 0

        chunks.forEachIndexed { index, text ->
            val chunkId = "${doc.docId}#${index + 1}"
            val chunkHash = sha256(text)
            val existingHash = existingHashesByChunkOrder[index + 1]

            if (existingHash == chunkHash) {
                return@forEachIndexed
            }

            mapper.upsertChunk(
                chunkId = chunkId,
                docId = doc.docId,
                chunkOrder = index + 1,
                chunkText = text,
                chunkHash = chunkHash,
            )
            chunksUpserted += 1

            val vector = runCatching { embedClient.embed(text) }.getOrElse {
                meterRegistry.counter("chat_rag_index_embed_fail_total").increment()
                emptyList()
            }
            if (vector.isNotEmpty()) {
                mapper.upsertVector(
                    chunkId = chunkId,
                    model = model,
                    vectorBlob = objectMapper.writeValueAsBytes(vector),
                )
                vectorsUpserted += 1
            }
        }

        val nextOrder = chunks.size + 1
        mapper.deleteVectorsByDocFromOrder(doc.docId, nextOrder)
        mapper.deleteChunksByDocFromOrder(doc.docId, nextOrder)

        return ChunkUpsertStats(
            chunksUpserted = chunksUpserted,
            vectorsUpserted = vectorsUpserted,
        )
    }

    private fun deleteStaleDocuments(liveDocIds: Set<String>): Int {
        val allIndexed = mapper.listAllIndexedDocIds()

        val stale = allIndexed.filter { it !in liveDocIds }
        stale.forEach { docId ->
            mapper.deleteVectorsByDoc(docId)
            mapper.deleteChunksByDoc(docId)
            mapper.deleteDocument(docId)
        }
        return stale.size
    }

    private fun chunkText(text: String, chunkSize: Int = 320, overlap: Int = 60): List<String> {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            return emptyList()
        }

        if (normalized.length <= chunkSize) {
            return listOf(normalized)
        }

        val result = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            val end = (start + chunkSize).coerceAtMost(normalized.length)
            val chunk = normalized.substring(start, end).trim()
            if (chunk.isNotEmpty()) {
                result += chunk
            }
            if (end >= normalized.length) {
                break
            }
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return result
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    data class SourceDoc(
        val docId: String,
        val sourceType: String,
        val refId: Long?,
        val city: String?,
        val title: String,
        val body: String,
        val sourceUpdatedAt: LocalDateTime?,
    ) {
        val hash: String = run {
            val digest = MessageDigest.getInstance("SHA-256")
            val payload = buildString {
                append(docId)
                append('|')
                append(sourceType)
                append('|')
                append(refId ?: "-")
                append('|')
                append(city ?: "-")
                append('|')
                append(title)
                append('|')
                append(body)
                append('|')
                append(sourceUpdatedAt?.toString() ?: "-")
            }
            digest.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }

    private data class ChunkUpsertStats(
        val chunksUpserted: Int,
        val vectorsUpserted: Int,
    )
}

enum class RagIndexBuildMode {
    FULL,
    INCREMENTAL,
}

data class RagIndexBuildData(
    val mode: String,
    val scanned: Int,
    val updated_docs: Int,
    val skipped_docs: Int,
    val upserted_chunks: Int,
    val upserted_vectors: Int,
    val removed_docs: Int,
    val elapsed_ms: Long,
    val speedup_vs_full: Double?,
)

data class IndexedHashRow(
    val docId: String,
    val docHash: String,
)

data class PropertySourceRow(
    val id: Long,
    val name: String,
    val city: String?,
    val country: String?,
    val updatedAt: LocalDateTime?,
    val rating: Double,
    val minPrice: Long,
)

data class TicketSourceRow(
    val id: Long,
    val name: String,
    val city: String?,
    val productType: String?,
    val updatedAt: LocalDateTime?,
    val nextStart: LocalDateTime?,
    val maxRemain: Int,
)

data class PackageSourceRow(
    val id: Long,
    val name: String,
    val amountTotal: Long,
    val currency: String?,
    val updatedAt: LocalDateTime?,
    val componentCity: String?,
    val componentCityCount: Int,
)

data class PoiSourceRow(
    val id: Long,
    val name: String,
    val category: String?,
    val city: String?,
    val createdAt: LocalDateTime?,
)

data class ChunkHashRow(
    val chunkOrder: Int,
    val chunkHash: String,
)

@Mapper
interface RagIndexBuilderMapper {
    @Select("SELECT MAX(source_updated_at) FROM travel_doc WHERE source_type = #{sourceType}")
    fun findLatestIndexedAt(@Param("sourceType") sourceType: String): LocalDateTime?

    @Select(
        """
        <script>
        SELECT doc_id AS docId, doc_hash AS docHash
        FROM travel_doc
        WHERE doc_id IN
        <foreach collection="docIds" item="docId" open="(" separator="," close=")">
          #{docId}
        </foreach>
        </script>
        """,
    )
    fun listExistingHashes(@Param("docIds") docIds: List<String>): List<IndexedHashRow>

    @Select(
        """
        <script>
        SELECT p.id,
               p.name,
               p.city,
               p.country,
               p.updated_at AS updatedAt,
               COALESCE(p.rating, 0.0) AS rating,
               COALESCE(MIN(rt.base_price), 0) AS minPrice
        FROM property p
        LEFT JOIN room_type rt ON rt.property_id = p.id AND rt.status = 'ACTIVE'
        WHERE p.status = 'ACTIVE'
          <if test="updatedAfter != null">
            AND p.updated_at &gt; #{updatedAfter}
          </if>
        GROUP BY p.id, p.name, p.city, p.country, p.updated_at, p.rating
        ORDER BY p.updated_at DESC
        <if test="limit != null">LIMIT #{limit}</if>
        </script>
        """,
    )
    fun listPropertyDocRows(
        @Param("updatedAfter") updatedAfter: LocalDateTime?,
        @Param("limit") limit: Int?,
    ): List<PropertySourceRow>

    @Select(
        """
        <script>
        SELECT p.id,
               p.name,
               p.city,
               p.product_type AS productType,
               p.updated_at AS updatedAt,
               MIN(te.start_time) AS nextStart,
               COALESCE(MAX(ti.total - ti.sold - ti.hold), 0) AS maxRemain
        FROM product p
        LEFT JOIN ticket_event te ON te.product_id = p.id AND te.status = 'ACTIVE'
        LEFT JOIN ticket_inventory ti ON ti.event_id = te.id
        WHERE p.status = 'ACTIVE'
          <if test="updatedAfter != null">
            AND p.updated_at &gt; #{updatedAfter}
          </if>
        GROUP BY p.id, p.name, p.city, p.product_type, p.updated_at
        ORDER BY p.updated_at DESC
        <if test="limit != null">LIMIT #{limit}</if>
        </script>
        """,
    )
    fun listTicketDocRows(
        @Param("updatedAfter") updatedAfter: LocalDateTime?,
        @Param("limit") limit: Int?,
    ): List<TicketSourceRow>

    @Select(
        """
        <script>
        SELECT pp.id,
               pp.name,
               pp.amount_total AS amountTotal,
               pp.currency,
               pp.updated_at AS updatedAt,
               MAX(COALESCE(pr.city, prod.city)) AS componentCity,
               COUNT(DISTINCT COALESCE(pr.city, prod.city)) AS componentCityCount
        FROM package_product pp
        LEFT JOIN package_product_component ppc ON ppc.package_id = pp.id
        LEFT JOIN room_type rt ON rt.id = ppc.room_type_id
        LEFT JOIN property pr ON pr.id = rt.property_id
        LEFT JOIN ticket_event te ON te.id = ppc.ticket_event_id
        LEFT JOIN product prod ON prod.id = te.product_id
        WHERE pp.status = 'ACTIVE'
          <if test="updatedAfter != null">
            AND pp.updated_at &gt; #{updatedAfter}
          </if>
        GROUP BY pp.id, pp.name, pp.amount_total, pp.currency, pp.updated_at
        ORDER BY pp.updated_at DESC
        <if test="limit != null">LIMIT #{limit}</if>
        </script>
        """,
    )
    fun listPackageDocRows(
        @Param("updatedAfter") updatedAfter: LocalDateTime?,
        @Param("limit") limit: Int?,
    ): List<PackageSourceRow>

    @Select(
        """
        <script>
        SELECT id, name, category, city, created_at AS createdAt
        FROM poi
        WHERE 1=1
          <if test="createdAfter != null">
            AND created_at &gt; #{createdAfter}
          </if>
        ORDER BY created_at DESC
        <if test="limit != null">LIMIT #{limit}</if>
        </script>
        """,
    )
    fun listPoiDocRows(
        @Param("createdAfter") createdAfter: LocalDateTime?,
        @Param("limit") limit: Int?,
    ): List<PoiSourceRow>

    @Insert(
        """
        INSERT INTO travel_doc (doc_id, source_type, ref_id, city, title, body, doc_hash, source_updated_at)
        VALUES (#{docId}, #{sourceType}, #{refId}, #{city}, #{title}, #{body}, #{docHash}, #{sourceUpdatedAt})
        ON DUPLICATE KEY UPDATE
          source_type = VALUES(source_type),
          ref_id = VALUES(ref_id),
          city = VALUES(city),
          title = VALUES(title),
          body = VALUES(body),
          doc_hash = VALUES(doc_hash),
          source_updated_at = VALUES(source_updated_at),
          updated_at = CURRENT_TIMESTAMP(3)
        """,
    )
    fun upsertDocument(
        @Param("docId") docId: String,
        @Param("sourceType") sourceType: String,
        @Param("refId") refId: Long?,
        @Param("city") city: String?,
        @Param("title") title: String,
        @Param("body") body: String,
        @Param("docHash") docHash: String,
        @Param("sourceUpdatedAt") sourceUpdatedAt: LocalDateTime?,
    ): Int

    @Select(
        """
        SELECT chunk_order AS chunkOrder, chunk_hash AS chunkHash
        FROM travel_doc_chunk
        WHERE doc_id = #{docId}
        """,
    )
    fun listChunkHashes(@Param("docId") docId: String): List<ChunkHashRow>

    @Insert(
        """
        INSERT INTO travel_doc_chunk (chunk_id, doc_id, chunk_order, chunk_text, chunk_hash)
        VALUES (#{chunkId}, #{docId}, #{chunkOrder}, #{chunkText}, #{chunkHash})
        ON DUPLICATE KEY UPDATE
          chunk_text = VALUES(chunk_text),
          chunk_hash = VALUES(chunk_hash),
          updated_at = CURRENT_TIMESTAMP(3)
        """,
    )
    fun upsertChunk(
        @Param("chunkId") chunkId: String,
        @Param("docId") docId: String,
        @Param("chunkOrder") chunkOrder: Int,
        @Param("chunkText") chunkText: String,
        @Param("chunkHash") chunkHash: String,
    ): Int

    @Insert(
        """
        INSERT INTO travel_doc_vec (chunk_id, model, vector_blob)
        VALUES (#{chunkId}, #{model}, #{vectorBlob})
        ON DUPLICATE KEY UPDATE
          vector_blob = VALUES(vector_blob),
          updated_at = CURRENT_TIMESTAMP(3)
        """,
    )
    fun upsertVector(
        @Param("chunkId") chunkId: String,
        @Param("model") model: String,
        @Param("vectorBlob") vectorBlob: ByteArray,
    ): Int

    @Delete(
        """
        DELETE FROM travel_doc_vec
        WHERE chunk_id IN (
          SELECT chunk_id FROM travel_doc_chunk WHERE doc_id = #{docId} AND chunk_order >= #{nextOrder}
        )
        """,
    )
    fun deleteVectorsByDocFromOrder(
        @Param("docId") docId: String,
        @Param("nextOrder") nextOrder: Int,
    ): Int

    @Delete("DELETE FROM travel_doc_chunk WHERE doc_id = #{docId} AND chunk_order >= #{nextOrder}")
    fun deleteChunksByDocFromOrder(
        @Param("docId") docId: String,
        @Param("nextOrder") nextOrder: Int,
    ): Int

    @Select("SELECT doc_id FROM travel_doc")
    fun listAllIndexedDocIds(): List<String>

    @Delete(
        """
        DELETE FROM travel_doc_vec
        WHERE chunk_id IN (
          SELECT chunk_id FROM travel_doc_chunk WHERE doc_id = #{docId}
        )
        """,
    )
    fun deleteVectorsByDoc(@Param("docId") docId: String): Int

    @Delete("DELETE FROM travel_doc_chunk WHERE doc_id = #{docId}")
    fun deleteChunksByDoc(@Param("docId") docId: String): Int

    @Delete("DELETE FROM travel_doc WHERE doc_id = #{docId}")
    fun deleteDocument(@Param("docId") docId: String): Int
}
