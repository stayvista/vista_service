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
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class PoiService(
    private val mapper: PoiMapper,
    private val cache: SimpleTtlCache,
    private val meterRegistry: MeterRegistry,
    private val geohashPrefixPlanner: PoiGeohashPrefixPlanner,
    private val domainSupportService: DomainSupportService,
    private val objectMapper: ObjectMapper,
    @Value("\${stayvista.poi.nearby.cache-ttl-seconds:15}") private val nearbyCacheTtlSeconds: Long,
    @Value("\${stayvista.poi.nearby.scan-limit:2000}") private val nearbyScanLimit: Int,
) {
    companion object {
        private const val RADIUS_VIEWPORT_MAX_LAT_SPAN = 1.0
        private const val RADIUS_VIEWPORT_MAX_LNG_SPAN = 1.0
    }

    fun nearby(query: PoiNearbyQuery): PoiNearbyData {
        val normalizedCategory = query.category?.trim()?.takeIf { it.isNotEmpty() }
        val center = query.center ?: PoiCenter(query.bbox.centerLat(), query.bbox.centerLng())
        val effectiveRadius = query.radius_m?.takeIf {
            query.bbox.latSpan() <= RADIUS_VIEWPORT_MAX_LAT_SPAN &&
                query.bbox.lngSpan() <= RADIUS_VIEWPORT_MAX_LNG_SPAN
        }

        val cacheKey = nearbyCacheKey(
            query = query,
            normalizedCategory = normalizedCategory,
            center = center,
            effectiveRadius = effectiveRadius,
        )
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
            center = center,
        )

        val sorted = candidates
            .map { row ->
                val distance = haversineMeters(center.lat, center.lng, row.lat, row.lng)
                NearbyCandidate(row = row, distanceMeters = distance)
            }
            .filter { candidate ->
                val radius = effectiveRadius ?: return@filter true
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
                    rating_score = candidate.row.ratingScore.takeIf { it > 0.0 },
                    review_count = candidate.row.popularityScore.takeIf { it > 0 },
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

        val rows = mapper.listAdminPois(
            keyword = normalizedKeyword,
            keywordLike = keywordLike,
            limit = fetchLimit + 1,
            offset = offset.coerceAtLeast(0),
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

        val command = AdminPoiCreateCommand(
            name = normalized.name,
            category = normalized.category,
            city = normalized.city,
            lat = normalized.lat,
            lng = normalized.lng,
            address = normalized.address,
            description = normalized.description,
            imageUrls = writeImages(normalized.images),
            popularityScore = normalized.popularity_score,
            ratingScore = normalized.rating_score,
            active = normalized.active,
            geohash = geohash,
        )
        mapper.insertAdminPoi(command)

        val id = command.id ?: throw DomainException(ErrorCode.INTERNAL, "POI create failed")
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

        val affected = mapper.updateAdminPoi(
            AdminPoiUpdateCommand(
                id = poiId,
                name = merged.name,
                category = merged.category,
                city = merged.city,
                lat = merged.lat,
                lng = merged.lng,
                address = merged.address,
                description = merged.description,
                imageUrls = writeImages(merged.images),
                popularityScore = merged.popularityScore,
                ratingScore = merged.ratingScore,
                active = merged.active,
                geohash = PoiGeohash.encode(merged.lat, merged.lng, 9),
            ),
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
        val rows = mapper.listPoiCoordinatesForGeohashBackfill(batchSize)

        var updated = 0
        rows.forEach { row ->
            updated += mapper.updatePoiGeohash(row.id, PoiGeohash.encode(row.lat, row.lng, 9))
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
        center: PoiCenter?,
    ): List<PoiRecord> {
        return mapper.listNearbyCandidates(
            PoiNearbyCandidateQuery(
                swLat = bbox.swLat,
                neLat = bbox.neLat,
                swLng = bbox.swLng,
                neLng = bbox.neLng,
                category = category,
                geohashPrefixes = geohashPrefixPlanner.resolvePrefixes(bbox),
                centerLat = center?.lat,
                centerLng = center?.lng,
                limit = fetchLimit.coerceIn(200, 5000),
            ),
        )
            .map { row -> row.toPoiRecord() }
    }

    private fun loadPoi(id: Long, activeOnly: Boolean): PoiRecord? {
        return mapper.findPoiById(id, activeOnly)?.toPoiRecord()
    }

    private fun loadRelatedProperties(poi: PoiRecord): List<PoiRelatedProperty> {
        val rows = mapper.listRelatedProperties(poi.city)

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
        return mapper.listRelatedProducts(poi.city)
            .map { row ->
                PoiRelatedProduct(
                    product_id = row.id,
                    name = row.name,
                    category = row.productType,
                    city = row.city,
                )
            }
    }

    private fun nearbyCacheKey(
        query: PoiNearbyQuery,
        normalizedCategory: String?,
        center: PoiCenter,
        effectiveRadius: Int?,
    ): String {
        val raw = listOf(
            "bbox=${query.bbox.normalized()}",
            "category=${normalizedCategory.orEmpty()}",
            "sort=${query.sort.apiValue}",
            "limit=${query.limit}",
            "offset=${query.offset}",
            "center=%.6f,%.6f".format(center.lat, center.lng),
            "radius=${effectiveRadius ?: ""}",
        ).joinToString("&")

        return "nearby:${sha256(raw)}:${normalizedCategory.orEmpty()}:${query.sort.apiValue}:${query.limit}:${query.offset}:${effectiveRadius ?: 0}"
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
        cache.invalidatePrefix("nearby:")
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

    private fun PoiRecordRow.toPoiRecord(): PoiRecord {
        return PoiRecord(
            id = id,
            name = name,
            category = category,
            city = city,
            lat = lat,
            lng = lng,
            address = address,
            description = description,
            images = readImages(imageUrls),
            popularityScore = popularityScore,
            ratingScore = ratingScore,
            active = active,
            geohash = geohash,
        )
    }
}

data class AdminPoiCreateCommand(
    var id: Long? = null,
    val name: String,
    val category: String?,
    val city: String?,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val description: String?,
    val imageUrls: String?,
    val popularityScore: Int,
    val ratingScore: Double,
    val active: Boolean,
    val geohash: String?,
)

data class AdminPoiUpdateCommand(
    val id: Long,
    val name: String,
    val category: String?,
    val city: String?,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val description: String?,
    val imageUrls: String?,
    val popularityScore: Int,
    val ratingScore: Double,
    val active: Boolean,
    val geohash: String?,
)

data class PoiNearbyCandidateQuery(
    val swLat: Double,
    val neLat: Double,
    val swLng: Double,
    val neLng: Double,
    val category: String?,
    val geohashPrefixes: List<String>,
    val centerLat: Double?,
    val centerLng: Double?,
    val limit: Int,
)

data class PoiCoordinateRow(
    val id: Long,
    val lat: Double,
    val lng: Double,
)

data class PoiRecordRow(
    val id: Long,
    val name: String,
    val category: String?,
    val city: String?,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val description: String?,
    val imageUrls: String?,
    val popularityScore: Int,
    val ratingScore: Double,
    val active: Boolean,
    val geohash: String?,
)

data class PoiRelatedPropertyRow(
    val id: Long,
    val name: String,
    val city: String?,
    val rating: Double,
    val thumbnailUrl: String?,
    val lat: Double?,
    val lng: Double?,
)

data class PoiRelatedProductRow(
    val id: Long,
    val name: String,
    val productType: String,
    val city: String?,
)

@Mapper
interface PoiMapper {
    @Select(
        """
        <script>
        SELECT id, name, category, city, lat, lng, address, active
        FROM poi
        WHERE 1 = 1
          <if test="keyword != null">
            AND (name LIKE #{keywordLike} OR category LIKE #{keywordLike} OR city LIKE #{keywordLike})
          </if>
        ORDER BY id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """,
    )
    fun listAdminPois(
        @Param("keyword") keyword: String?,
        @Param("keywordLike") keywordLike: String?,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<AdminPoiSummary>

    @Insert(
        """
        INSERT INTO poi(
          name, category, city, lat, lng, address, description,
          image_urls, popularity_score, rating_score, active, geohash
        )
        VALUES (
          #{name}, #{category}, #{city}, #{lat}, #{lng}, #{address}, #{description},
          #{imageUrls}, #{popularityScore}, #{ratingScore}, #{active}, #{geohash}
        )
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertAdminPoi(command: AdminPoiCreateCommand): Int

    @Update(
        """
        UPDATE poi
        SET name = #{name},
            category = #{category},
            city = #{city},
            lat = #{lat},
            lng = #{lng},
            address = #{address},
            description = #{description},
            image_urls = #{imageUrls},
            popularity_score = #{popularityScore},
            rating_score = #{ratingScore},
            active = #{active},
            geohash = #{geohash}
        WHERE id = #{id}
        """,
    )
    fun updateAdminPoi(command: AdminPoiUpdateCommand): Int

    @Select(
        """
        SELECT id, lat, lng
        FROM poi
        WHERE geohash IS NULL OR geohash = ''
        ORDER BY id
        LIMIT #{limit}
        """,
    )
    fun listPoiCoordinatesForGeohashBackfill(@Param("limit") limit: Int): List<PoiCoordinateRow>

    @Update("UPDATE poi SET geohash = #{geohash} WHERE id = #{id}")
    fun updatePoiGeohash(
        @Param("id") id: Long,
        @Param("geohash") geohash: String,
    ): Int

    @Select(
        """
        <script>
        SELECT id,
               name,
               category,
               city,
               lat,
               lng,
               address,
               description,
               image_urls AS imageUrls,
               popularity_score AS popularityScore,
               COALESCE(rating_score, 0) AS ratingScore,
               active,
               geohash
        FROM poi
        WHERE active = 1
          AND lat BETWEEN #{swLat} AND #{neLat}
          AND lng BETWEEN #{swLng} AND #{neLng}
          <if test="category != null">
            AND category = #{category}
          </if>
          <if test="geohashPrefixes != null and !geohashPrefixes.isEmpty()">
            AND (
              geohash IS NULL
              OR geohash = ''
              <foreach collection="geohashPrefixes" item="prefix">
                OR geohash LIKE CONCAT(#{prefix}, '%')
              </foreach>
            )
          </if>
        ORDER BY
          <if test="centerLat != null and centerLng != null">
            ((lat - #{centerLat}) * (lat - #{centerLat}) + (lng - #{centerLng}) * (lng - #{centerLng})) ASC,
          </if>
          id
        LIMIT #{limit}
        </script>
        """,
    )
    fun listNearbyCandidates(query: PoiNearbyCandidateQuery): List<PoiRecordRow>

    @Select(
        """
        <script>
        SELECT id,
               name,
               category,
               city,
               lat,
               lng,
               address,
               description,
               image_urls AS imageUrls,
               popularity_score AS popularityScore,
               COALESCE(rating_score, 0) AS ratingScore,
               active,
               geohash
        FROM poi
        WHERE id = #{id}
          <if test="activeOnly">
            AND active = 1
          </if>
        LIMIT 1
        </script>
        """,
    )
    fun findPoiById(
        @Param("id") id: Long,
        @Param("activeOnly") activeOnly: Boolean,
    ): PoiRecordRow?

    @Select(
        """
        <script>
        SELECT id,
               name,
               city,
               COALESCE(rating, 0) AS rating,
               thumbnail_url AS thumbnailUrl,
               lat,
               lng
        FROM property
        WHERE status = 'ACTIVE'
          <if test="city != null">
            AND city = #{city}
          </if>
        ORDER BY id DESC
        LIMIT 40
        </script>
        """,
    )
    fun listRelatedProperties(@Param("city") city: String?): List<PoiRelatedPropertyRow>

    @Select(
        """
        <script>
        SELECT id, name, product_type AS productType, city
        FROM product
        WHERE status = 'ACTIVE'
          <if test="city != null">
            AND city = #{city}
          </if>
        ORDER BY id DESC
        LIMIT 4
        </script>
        """,
    )
    fun listRelatedProducts(@Param("city") city: String?): List<PoiRelatedProductRow>
}
