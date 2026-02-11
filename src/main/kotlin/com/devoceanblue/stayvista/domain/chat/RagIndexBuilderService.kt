package com.devoceanblue.stayvista.domain.chat

import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class RagIndexBuilderService(
    private val jdbcTemplate: JdbcTemplate,
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
        return jdbcTemplate.query(
            "SELECT MAX(source_updated_at) AS last_updated FROM travel_doc WHERE source_type = ?",
            { rs, _ -> rs.getTimestamp("last_updated")?.toLocalDateTime() },
            sourceType,
        ).firstOrNull()
    }

    private fun loadExistingHashes(docIds: List<String>): Map<String, String> {
        if (docIds.isEmpty()) return emptyMap()

        val placeholders = docIds.joinToString(",") { "?" }
        val sql = "SELECT doc_id, doc_hash FROM travel_doc WHERE doc_id IN ($placeholders)"

        return jdbcTemplate.query(
            sql,
            { rs, _ -> rs.getString("doc_id") to rs.getString("doc_hash") },
            *docIds.toTypedArray(),
        ).toMap()
    }

    private fun loadPropertyDocs(limit: Int?, updatedAfter: LocalDateTime?): List<SourceDoc> {
        val params = mutableListOf<Any>()
        val sql = StringBuilder(
            """
            SELECT p.id, p.name, p.city, p.country, p.updated_at,
                   COALESCE(p.rating, 0.0) AS rating,
                   COALESCE(MIN(rt.base_price), 0) AS min_price
            FROM property p
            LEFT JOIN room_type rt ON rt.property_id = p.id AND rt.status = 'ACTIVE'
            WHERE p.status = 'ACTIVE'
            """.trimIndent(),
        )

        if (updatedAfter != null) {
            sql.append(" AND p.updated_at > ?")
            params += java.sql.Timestamp.valueOf(updatedAfter)
        }

        sql.append(" GROUP BY p.id, p.name, p.city, p.country, p.updated_at, p.rating")
        sql.append(" ORDER BY p.updated_at DESC")

        if (limit != null) {
            sql.append(" LIMIT ?")
            params += limit
        }

        return jdbcTemplate.query(sql.toString(), { rs, _ ->
            val propertyId = rs.getLong("id")
            val city = rs.getString("city")
            val country = rs.getString("country")
            val rating = rs.getBigDecimal("rating")?.toDouble() ?: 0.0
            val minPrice = rs.getLong("min_price")
            val updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime()

            val title = rs.getString("name")
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
        }, *params.toTypedArray())
    }

    private fun loadTicketDocs(limit: Int?, updatedAfter: LocalDateTime?): List<SourceDoc> {
        val params = mutableListOf<Any>()
        val sql = StringBuilder(
            """
            SELECT p.id, p.name, p.city, p.product_type, p.updated_at,
                   MIN(te.start_time) AS next_start,
                   COALESCE(MAX(ti.total - ti.sold - ti.hold), 0) AS max_remain
            FROM product p
            LEFT JOIN ticket_event te ON te.product_id = p.id AND te.status = 'ACTIVE'
            LEFT JOIN ticket_inventory ti ON ti.event_id = te.id
            WHERE p.status = 'ACTIVE'
            """.trimIndent(),
        )

        if (updatedAfter != null) {
            sql.append(" AND p.updated_at > ?")
            params += java.sql.Timestamp.valueOf(updatedAfter)
        }

        sql.append(" GROUP BY p.id, p.name, p.city, p.product_type, p.updated_at")
        sql.append(" ORDER BY p.updated_at DESC")

        if (limit != null) {
            sql.append(" LIMIT ?")
            params += limit
        }

        return jdbcTemplate.query(sql.toString(), { rs, _ ->
            val productId = rs.getLong("id")
            val city = rs.getString("city")
            val productType = rs.getString("product_type") ?: "TICKET"
            val nextStart = rs.getTimestamp("next_start")?.toLocalDateTime()
            val remain = rs.getInt("max_remain")
            val updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime()

            val title = rs.getString("name")
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
        }, *params.toTypedArray())
    }

    private fun loadPackageDocs(limit: Int?, updatedAfter: LocalDateTime?): List<SourceDoc> {
        val params = mutableListOf<Any>()
        val sql = StringBuilder(
            """
            SELECT id, name, status, amount_total, currency, updated_at
            FROM package_product
            WHERE status = 'ACTIVE'
            """.trimIndent(),
        )

        if (updatedAfter != null) {
            sql.append(" AND updated_at > ?")
            params += java.sql.Timestamp.valueOf(updatedAfter)
        }

        sql.append(" ORDER BY updated_at DESC")
        if (limit != null) {
            sql.append(" LIMIT ?")
            params += limit
        }

        return jdbcTemplate.query(sql.toString(), { rs, _ ->
            val packageId = rs.getLong("id")
            val amount = rs.getLong("amount_total")
            val currency = rs.getString("currency") ?: "KRW"
            val updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime()

            val title = rs.getString("name")
            val body = buildString {
                append("패키지 ")
                append(title)
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
                city = null,
                title = title,
                body = body,
                sourceUpdatedAt = updatedAt,
            )
        }, *params.toTypedArray())
    }

    private fun loadPoiDocs(limit: Int?, createdAfter: LocalDateTime?): List<SourceDoc> {
        val params = mutableListOf<Any>()
        val sql = StringBuilder(
            """
            SELECT id, name, category, city, created_at
            FROM poi
            WHERE 1=1
            """.trimIndent(),
        )

        if (createdAfter != null) {
            sql.append(" AND created_at > ?")
            params += java.sql.Timestamp.valueOf(createdAfter)
        }

        sql.append(" ORDER BY created_at DESC")
        if (limit != null) {
            sql.append(" LIMIT ?")
            params += limit
        }

        return jdbcTemplate.query(sql.toString(), { rs, _ ->
            val poiId = rs.getLong("id")
            val city = rs.getString("city")
            val category = rs.getString("category") ?: "spot"
            val createdAt = rs.getTimestamp("created_at")?.toLocalDateTime()

            val title = rs.getString("name")
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
        }, *params.toTypedArray())
    }

    private fun upsertDocument(doc: SourceDoc) {
        jdbcTemplate.update(
            """
            INSERT INTO travel_doc (doc_id, source_type, ref_id, city, title, body, doc_hash, source_updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              source_type = VALUES(source_type),
              ref_id = VALUES(ref_id),
              city = VALUES(city),
              title = VALUES(title),
              body = VALUES(body),
              doc_hash = VALUES(doc_hash),
              source_updated_at = VALUES(source_updated_at),
              updated_at = CURRENT_TIMESTAMP(3)
            """.trimIndent(),
            doc.docId,
            doc.sourceType,
            doc.refId,
            doc.city,
            doc.title,
            doc.body,
            doc.hash,
            doc.sourceUpdatedAt?.let { java.sql.Timestamp.valueOf(it) },
        )
    }

    private fun upsertChunksAndVectors(doc: SourceDoc, model: String): ChunkUpsertStats {
        val existingHashesByChunkOrder = jdbcTemplate.query(
            """
            SELECT chunk_order, chunk_hash
            FROM travel_doc_chunk
            WHERE doc_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getInt("chunk_order") to rs.getString("chunk_hash") },
            doc.docId,
        ).toMap()

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

            jdbcTemplate.update(
                """
                INSERT INTO travel_doc_chunk (chunk_id, doc_id, chunk_order, chunk_text, chunk_hash)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  chunk_text = VALUES(chunk_text),
                  chunk_hash = VALUES(chunk_hash),
                  updated_at = CURRENT_TIMESTAMP(3)
                """.trimIndent(),
                chunkId,
                doc.docId,
                index + 1,
                text,
                chunkHash,
            )
            chunksUpserted += 1

            val vector = runCatching { embedClient.embed(text) }.getOrElse {
                meterRegistry.counter("chat_rag_index_embed_fail_total").increment()
                emptyList()
            }
            if (vector.isNotEmpty()) {
                jdbcTemplate.update(
                    """
                    INSERT INTO travel_doc_vec (chunk_id, model, vector_blob)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      vector_blob = VALUES(vector_blob),
                      updated_at = CURRENT_TIMESTAMP(3)
                    """.trimIndent(),
                    chunkId,
                    model,
                    objectMapper.writeValueAsBytes(vector),
                )
                vectorsUpserted += 1
            }
        }

        val nextOrder = chunks.size + 1
        jdbcTemplate.update(
            """
            DELETE FROM travel_doc_vec
            WHERE chunk_id IN (
              SELECT chunk_id FROM travel_doc_chunk WHERE doc_id = ? AND chunk_order >= ?
            )
            """.trimIndent(),
            doc.docId,
            nextOrder,
        )
        jdbcTemplate.update(
            "DELETE FROM travel_doc_chunk WHERE doc_id = ? AND chunk_order >= ?",
            doc.docId,
            nextOrder,
        )

        return ChunkUpsertStats(
            chunksUpserted = chunksUpserted,
            vectorsUpserted = vectorsUpserted,
        )
    }

    private fun deleteStaleDocuments(liveDocIds: Set<String>): Int {
        val allIndexed = jdbcTemplate.query(
            "SELECT doc_id FROM travel_doc",
            { rs, _ -> rs.getString("doc_id") },
        )

        val stale = allIndexed.filter { it !in liveDocIds }
        stale.forEach { docId ->
            jdbcTemplate.update(
                """
                DELETE FROM travel_doc_vec
                WHERE chunk_id IN (
                  SELECT chunk_id FROM travel_doc_chunk WHERE doc_id = ?
                )
                """.trimIndent(),
                docId,
            )
            jdbcTemplate.update("DELETE FROM travel_doc_chunk WHERE doc_id = ?", docId)
            jdbcTemplate.update("DELETE FROM travel_doc WHERE doc_id = ?", docId)
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

    private data class SourceDoc(
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
