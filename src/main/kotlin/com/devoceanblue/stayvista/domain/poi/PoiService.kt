package com.devoceanblue.stayvista.domain.poi

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.cache.SimpleTtlCache
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import io.micrometer.core.instrument.MeterRegistry
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class PoiService(
    private val jdbcTemplate: JdbcTemplate,
    private val cache: SimpleTtlCache,
    private val meterRegistry: MeterRegistry,
    private val geohashPrefixPlanner: PoiGeohashPrefixPlanner,
    private val domainSupportService: DomainSupportService,
    private val objectMapper: ObjectMapper,
    @Value("\${stayvista.poi.nearby.cache-ttl-seconds:15}") private val nearbyCacheTtlSeconds: Long,
    @Value("\${stayvista.poi.nearby.scan-limit:2000}") private val nearbyScanLimit: Int,
) {
    fun nearby(query: PoiNearbyQuery): PoiNearbyData {
        val normalizedCategory = query.category?.trim()?.takeIf { it.isNotEmpty() }
        val center = query.center ?: PoiCenter(query.bbox.centerLat(), query.bbox.centerLng())

        val cacheKey = nearbyCacheKey(query = query, normalizedCategory = normalizedCategory, center = center)
        cache.get<PoiNearbyData>(cacheKey)?.let {
            meterRegistry.counter("cache_hit_rate_nearby", "result", "hit").increment()
            return it
        }
        meterRegistry.counter("cache_hit_rate_nearby", "result", "miss").increment()

        val fetchLimit = ((query.offset + query.limit + 1) * 4).coerceIn(400, nearbyScanLimit.coerceAtLeast(400))
        val candidates = loadNearbyCandidates(
            bbox = query.bbox,
            category = normalizedCategory,
            fetchLimit = fetchLimit,
        )

        val sorted = candidates
            .map { row ->
                val distance = haversineMeters(center.lat, center.lng, row.lat, row.lng)
                NearbyCandidate(row = row, distanceMeters = distance)
            }
            .filter { candidate ->
                val radius = query.radius_m ?: return@filter true
                candidate.distanceMeters <= radius
            }
            .sortedWith(
                when (query.sort) {
                    PoiSort.DISTANCE -> compareBy<NearbyCandidate> { it.distanceMeters }
                        .thenByDescending { it.row.popularityScore }
                        .thenByDescending { it.row.ratingScore }
                        .thenBy { it.row.id }
                    PoiSort.POPULARITY -> compareByDescending<NearbyCandidate> { it.row.popularityScore }
                        .thenBy { it.distanceMeters }
                        .thenByDescending { it.row.ratingScore }
                        .thenBy { it.row.id }
                    PoiSort.RATING -> compareByDescending<NearbyCandidate> { it.row.ratingScore }
                        .thenBy { it.distanceMeters }
                        .thenByDescending { it.row.popularityScore }
                        .thenBy { it.row.id }
                },
            )

        val page = sorted.drop(query.offset)
            .take(query.limit + 1)
        val hasMore = page.size > query.limit
        val items = if (hasMore) page.dropLast(1) else page

        val data = PoiNearbyData(
            items = items.map { candidate ->
                PoiNearbyItem(
                    id = candidate.row.id,
                    name = candidate.row.name,
                    category = candidate.row.category,
                    lat = candidate.row.lat,
                    lng = candidate.row.lng,
                    distance_m = candidate.distanceMeters.roundToInt(),
                    preview = PoiNearbyPreview(
                        thumbnail_url = candidate.row.images.firstOrNull(),
                        address = candidate.row.address,
                        snippet = candidate.row.description?.take(120),
                    ),
                )
            },
            meta = PoiNearbyMeta(
                bbox = query.bbox.normalized(),
                returned = items.size,
                has_more = hasMore,
                offset = query.offset,
                limit = query.limit,
            ),
        )

        cache.put(
            key = cacheKey,
            ttlMillis = nearbyCacheTtlSeconds.coerceIn(10, 30) * 1_000,
            value = data,
        )
        return data
    }

    fun getPoiDetail(poiId: Long): PoiDetailData {
        val poi = loadPoi(id = poiId, activeOnly = true) ?: throw DomainException(ErrorCode.NOT_FOUND, "POI not found")

        val relatedProperties = loadRelatedProperties(poi)
        val relatedProducts = loadRelatedProducts(poi)

        return PoiDetailData(
            id = poi.id,
            name = poi.name,
            category = poi.category,
            lat = poi.lat,
            lng = poi.lng,
            address = poi.address,
            description = poi.description,
            images = poi.images,
            links = PoiExternalLinks(
                naver = buildNaverLink(poi),
                google = "https://www.google.com/maps/search/?api=1&query=${poi.lat},${poi.lng}",
                osm = "https://www.openstreetmap.org/?mlat=${poi.lat}&mlon=${poi.lng}#map=16/${poi.lat}/${poi.lng}",
            ),
            related = PoiRelatedHints(
                properties = relatedProperties,
                products = relatedProducts,
            ),
        )
    }

    fun listAdminPois(limit: Int, offset: Int, keyword: String?): AdminPoiListData {
        val fetchLimit = limit.coerceIn(1, 200)
        val normalizedKeyword = keyword?.trim()?.takeIf { it.isNotBlank() }
        val keywordLike = normalizedKeyword?.let { "%$it%" }

        val rows = jdbcTemplate.query(
            """
            SELECT id, name, category, city, lat, lng, address, active
            FROM poi
            WHERE (? IS NULL OR name LIKE ? OR category LIKE ? OR city LIKE ?)
            ORDER BY id DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            { rs, _ ->
                AdminPoiSummary(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    category = rs.getString("category"),
                    city = rs.getString("city"),
                    lat = rs.getBigDecimal("lat").toDouble(),
                    lng = rs.getBigDecimal("lng").toDouble(),
                    address = rs.getString("address"),
                    active = rs.getBoolean("active"),
                )
            },
            normalizedKeyword,
            keywordLike,
            keywordLike,
            keywordLike,
            fetchLimit + 1,
            offset.coerceAtLeast(0),
        )

        val hasMore = rows.size > fetchLimit
        val items = if (hasMore) rows.dropLast(1) else rows
        return AdminPoiListData(
            items = items,
            limit = fetchLimit,
            offset = offset.coerceAtLeast(0),
            has_more = hasMore,
        )
    }

    fun getAdminPoi(poiId: Long): AdminPoiDetail {
        val poi = loadPoi(id = poiId, activeOnly = false) ?: throw DomainException(ErrorCode.NOT_FOUND, "POI not found")
        return AdminPoiDetail(
            id = poi.id,
            name = poi.name,
            category = poi.category,
            city = poi.city,
            lat = poi.lat,
            lng = poi.lng,
            address = poi.address,
            description = poi.description,
            images = poi.images,
            popularity_score = poi.popularityScore,
            rating_score = poi.ratingScore,
            active = poi.active,
        )
    }

    @Transactional
    fun createAdminPoi(request: AdminPoiCreateRequest): Long {
        val normalized = normalizeCreateRequest(request)
        val geohash = PoiGeohash.encode(normalized.lat, normalized.lng, 9)

        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val statement = connection.prepareStatement(
                """
                INSERT INTO poi(
                  name, category, city, lat, lng, address, description,
                  image_urls, popularity_score, rating_score, active, geohash
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf("id"),
            )
            statement.setString(1, normalized.name)
            statement.setString(2, normalized.category)
            statement.setString(3, normalized.city)
            statement.setBigDecimal(4, normalized.lat.toBigDecimal())
            statement.setBigDecimal(5, normalized.lng.toBigDecimal())
            statement.setString(6, normalized.address)
            statement.setString(7, normalized.description)
            statement.setString(8, writeImages(normalized.images))
            statement.setInt(9, normalized.popularity_score)
            statement.setBigDecimal(10, normalized.rating_score.toBigDecimal())
            statement.setBoolean(11, normalized.active)
            statement.setString(12, geohash)
            statement
        }, keyHolder)

        val id = keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "POI create failed")
        domainSupportService.appendOutbox(
            aggregateType = "POI",
            aggregateId = id.toString(),
            eventType = "PoiUpserted",
            payload = mapOf(
                "poi_id" to id,
                "name" to normalized.name,
                "active" to normalized.active,
            ),
        )

        cacheInvalidateNearby()
        return id
    }

    @Transactional
    fun patchAdminPoi(poiId: Long, request: AdminPoiPatchRequest) {
        val current = loadPoi(id = poiId, activeOnly = false) ?: throw DomainException(ErrorCode.NOT_FOUND, "POI not found")
        val merged = current.copy(
            name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: current.name,
            category = request.category?.trim()?.takeIf { it.isNotEmpty() } ?: current.category,
            city = request.city?.trim()?.takeIf { it.isNotEmpty() } ?: current.city,
            lat = request.lat ?: current.lat,
            lng = request.lng ?: current.lng,
            address = request.address?.trim()?.takeIf { it.isNotEmpty() } ?: current.address,
            description = request.description?.trim()?.takeIf { it.isNotEmpty() } ?: current.description,
            images = request.images?.mapNotNull { it.trim().takeIf(String::isNotBlank) } ?: current.images,
            popularityScore = request.popularity_score ?: current.popularityScore,
            ratingScore = request.rating_score ?: current.ratingScore,
            active = request.active ?: current.active,
        )

        val affected = jdbcTemplate.update(
            """
            UPDATE poi
            SET name = ?,
                category = ?,
                city = ?,
                lat = ?,
                lng = ?,
                address = ?,
                description = ?,
                image_urls = ?,
                popularity_score = ?,
                rating_score = ?,
                active = ?,
                geohash = ?
            WHERE id = ?
            """.trimIndent(),
            merged.name,
            merged.category,
            merged.city,
            merged.lat.toBigDecimal(),
            merged.lng.toBigDecimal(),
            merged.address,
            merged.description,
            writeImages(merged.images),
            merged.popularityScore,
            merged.ratingScore.toBigDecimal(),
            merged.active,
            PoiGeohash.encode(merged.lat, merged.lng, 9),
            poiId,
        )

        if (affected == 0) {
            throw DomainException(ErrorCode.NOT_FOUND, "POI not found")
        }

        domainSupportService.appendOutbox(
            aggregateType = "POI",
            aggregateId = poiId.toString(),
            eventType = "PoiUpserted",
            payload = mapOf(
                "poi_id" to poiId,
                "name" to merged.name,
                "active" to merged.active,
            ),
        )

        cacheInvalidateNearby()
    }

    @Transactional
    fun backfillGeohash(limit: Int): PoiGeohashBackfillData {
        val batchSize = limit.coerceIn(1, 5000)
        val rows = jdbcTemplate.query(
            """
            SELECT id, lat, lng
            FROM poi
            WHERE geohash IS NULL OR geohash = ''
            ORDER BY id
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                PoiCoordinate(
                    id = rs.getLong("id"),
                    lat = rs.getBigDecimal("lat").toDouble(),
                    lng = rs.getBigDecimal("lng").toDouble(),
                )
            },
            batchSize,
        )

        var updated = 0
        rows.forEach { row ->
            updated += jdbcTemplate.update(
                "UPDATE poi SET geohash = ? WHERE id = ?",
                PoiGeohash.encode(row.lat, row.lng, 9),
                row.id,
            )
        }

        if (updated > 0) {
            cacheInvalidateNearby()
        }

        return PoiGeohashBackfillData(
            scanned = rows.size,
            updated = updated,
        )
    }

    private fun loadNearbyCandidates(
        bbox: PoiBoundingBox,
        category: String?,
        fetchLimit: Int,
    ): List<PoiRecord> {
        val prefixes = geohashPrefixPlanner.resolvePrefixes(bbox)

        val sql = StringBuilder(
            """
            SELECT id, name, category, city, lat, lng, address, description,
                   image_urls, popularity_score, rating_score, active, geohash
            FROM poi
            WHERE active = 1
              AND lat BETWEEN ? AND ?
              AND lng BETWEEN ? AND ?
            """.trimIndent(),
        )

        val params = mutableListOf<Any>(
            bbox.swLat,
            bbox.neLat,
            bbox.swLng,
            bbox.neLng,
        )

        if (!category.isNullOrBlank()) {
            sql.append(" AND category = ?")
            params += category
        }

        if (prefixes.isNotEmpty()) {
            sql.append(" AND (")
            prefixes.forEachIndexed { index, prefix ->
                if (index > 0) {
                    sql.append(" OR ")
                }
                sql.append("geohash LIKE ?")
                params += "$prefix%"
            }
            sql.append(")")
        }

        sql.append(" ORDER BY id LIMIT ?")
        params += fetchLimit.coerceIn(200, 5000)

        return jdbcTemplate.query(
            sql.toString(),
            { rs, _ ->
                PoiRecord(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    category = rs.getString("category"),
                    city = rs.getString("city"),
                    lat = rs.getBigDecimal("lat").toDouble(),
                    lng = rs.getBigDecimal("lng").toDouble(),
                    address = rs.getString("address"),
                    description = rs.getString("description"),
                    images = readImages(rs.getString("image_urls")),
                    popularityScore = rs.getInt("popularity_score"),
                    ratingScore = rs.getBigDecimal("rating_score")?.toDouble() ?: 0.0,
                    active = rs.getBoolean("active"),
                    geohash = rs.getString("geohash"),
                )
            },
            *params.toTypedArray(),
        )
    }

    private fun loadPoi(id: Long, activeOnly: Boolean): PoiRecord? {
        val sql = buildString {
            append(
                """
                SELECT id, name, category, city, lat, lng, address, description,
                       image_urls, popularity_score, rating_score, active, geohash
                FROM poi
                WHERE id = ?
                """.trimIndent(),
            )
            if (activeOnly) {
                append(" AND active = 1")
            }
        }

        return jdbcTemplate.query(
            sql,
            { rs, _ ->
                PoiRecord(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    category = rs.getString("category"),
                    city = rs.getString("city"),
                    lat = rs.getBigDecimal("lat").toDouble(),
                    lng = rs.getBigDecimal("lng").toDouble(),
                    address = rs.getString("address"),
                    description = rs.getString("description"),
                    images = readImages(rs.getString("image_urls")),
                    popularityScore = rs.getInt("popularity_score"),
                    ratingScore = rs.getBigDecimal("rating_score")?.toDouble() ?: 0.0,
                    active = rs.getBoolean("active"),
                    geohash = rs.getString("geohash"),
                )
            },
            id,
        ).firstOrNull()
    }

    private fun loadRelatedProperties(poi: PoiRecord): List<PoiRelatedProperty> {
        val rows = jdbcTemplate.query(
            """
            SELECT id, name, city, rating, thumbnail_url, lat, lng
            FROM property
            WHERE status = 'ACTIVE'
              AND (? IS NULL OR city = ?)
            ORDER BY id DESC
            LIMIT 40
            """.trimIndent(),
            { rs, _ ->
                RelatedPropertyRow(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    city = rs.getString("city"),
                    rating = rs.getBigDecimal("rating")?.toDouble() ?: 0.0,
                    thumbnailUrl = rs.getString("thumbnail_url"),
                    lat = rs.getBigDecimal("lat")?.toDouble(),
                    lng = rs.getBigDecimal("lng")?.toDouble(),
                )
            },
            poi.city,
            poi.city,
        )

        return rows
            .sortedBy { row ->
                if (row.lat == null || row.lng == null) {
                    Double.MAX_VALUE
                } else {
                    haversineMeters(poi.lat, poi.lng, row.lat, row.lng)
                }
            }
            .take(4)
            .map { row ->
                PoiRelatedProperty(
                    property_id = row.id,
                    name = row.name,
                    city = row.city,
                    rating = row.rating,
                    thumbnail_url = row.thumbnailUrl,
                )
            }
    }

    private fun loadRelatedProducts(poi: PoiRecord): List<PoiRelatedProduct> {
        val rows = jdbcTemplate.query(
            """
            SELECT id, name, product_type, city
            FROM product
            WHERE status = 'ACTIVE'
              AND (? IS NULL OR city = ?)
            ORDER BY id DESC
            LIMIT 4
            """.trimIndent(),
            { rs, _ ->
                PoiRelatedProduct(
                    product_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    category = rs.getString("product_type"),
                    city = rs.getString("city"),
                )
            },
            poi.city,
            poi.city,
        )
        return rows
    }

    private fun nearbyCacheKey(query: PoiNearbyQuery, normalizedCategory: String?, center: PoiCenter): String {
        val raw = listOf(
            "bbox=${query.bbox.normalized()}",
            "category=${normalizedCategory.orEmpty()}",
            "sort=${query.sort.apiValue}",
            "limit=${query.limit}",
            "offset=${query.offset}",
            "center=%.6f,%.6f".format(center.lat, center.lng),
            "radius=${query.radius_m ?: ""}",
        ).joinToString("&")

        return "nearby:${sha256(raw)}:${normalizedCategory.orEmpty()}:${query.sort.apiValue}:${query.limit}:${query.offset}:${query.radius_m ?: 0}"
    }

    private fun buildNaverLink(poi: PoiRecord): String {
        val query = listOfNotNull(poi.name, poi.address)
            .joinToString(" ")
            .trim()
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return "https://map.naver.com/p/search/$encoded"
    }

    private fun normalizeCreateRequest(request: AdminPoiCreateRequest): AdminPoiCreateRequest {
        return request.copy(
            name = request.name.trim(),
            category = request.category?.trim()?.takeIf { it.isNotEmpty() },
            city = request.city?.trim()?.takeIf { it.isNotEmpty() },
            address = request.address?.trim()?.takeIf { it.isNotEmpty() },
            description = request.description?.trim()?.takeIf { it.isNotEmpty() },
            images = request.images.mapNotNull { it.trim().takeIf(String::isNotBlank) },
        )
    }

    private fun writeImages(images: List<String>): String? {
        if (images.isEmpty()) {
            return null
        }
        return objectMapper.writeValueAsString(images)
    }

    private fun readImages(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val node = objectMapper.readTree(raw)
            if (node.isArray) {
                node.mapNotNull { child ->
                    if (!child.isTextual) {
                        null
                    } else {
                        child.asText().trim().takeIf { it.isNotEmpty() }
                    }
                }
            } else {
                raw.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }

    private fun cacheInvalidateNearby() {
        // short lived nearby cache. explicit wipe is unnecessary with current in-memory cache impl.
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class PoiCoordinate(
        val id: Long,
        val lat: Double,
        val lng: Double,
    )

    private data class NearbyCandidate(
        val row: PoiRecord,
        val distanceMeters: Double,
    )

    private data class PoiRecord(
        val id: Long,
        val name: String,
        val category: String?,
        val city: String?,
        val lat: Double,
        val lng: Double,
        val address: String?,
        val description: String?,
        val images: List<String>,
        val popularityScore: Int,
        val ratingScore: Double,
        val active: Boolean,
        val geohash: String?,
    )

    private data class RelatedPropertyRow(
        val id: Long,
        val name: String,
        val city: String?,
        val rating: Double,
        val thumbnailUrl: String?,
        val lat: Double?,
        val lng: Double?,
    )
}
