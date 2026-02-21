package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.cache.SimpleTtlCache
import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType
import com.devoceanblue.stayvista.domain.fx.FxService
import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SearchService(
    private val jdbcTemplate: JdbcTemplate,
    private val cache: SimpleTtlCache,
    private val meterRegistry: MeterRegistry,
    private val openSearchClient: OpenSearchClient,
    private val searchFacetService: SearchFacetService,
    private val fxService: FxService,
    @Value("\${stayvista.search.use-opensearch:true}") private val useOpenSearch: Boolean,
) {
    fun search(request: SearchRequest): SearchData {
        val startedAt = System.nanoTime()
        val normalizedRequest = resolvePlaceFilter(request.normalize())
        recordFilterUsage(normalizedRequest)
        val cacheKey = "search:v2:${sha256(normalized(normalizedRequest))}"
        cache.get<SearchData>(cacheKey)?.let {
            meterRegistry.counter("search_requests_total", "cache", "hit").increment()
            meterRegistry.timer("search_latency_ms", "source", "cache")
                .record(Duration.ofNanos(System.nanoTime() - startedAt))
            return it
        }
        meterRegistry.counter("search_requests_total", "cache", "miss").increment()

        var source = "db"
        val data = if (canUseOpenSearch(normalizedRequest)) {
            source = "opensearch"
            try {
                val openSearchData = openSearchClient.search(normalizedRequest)
                if (openSearchData.items.isEmpty()) {
                    val dbData = searchFromDb(normalizedRequest)
                    if (dbData.items.isNotEmpty()) {
                        source = "db_fallback"
                        meterRegistry.counter("search_opensearch_empty_fallback_total").increment()
                        dbData
                    } else {
                        enrichSearchData(openSearchData, normalizedRequest, startedAt)
                    }
                } else {
                    enrichSearchData(openSearchData, normalizedRequest, startedAt)
                }
            } catch (_: Exception) {
                source = "db_error_fallback"
                meterRegistry.counter("search_opensearch_errors_total").increment()
                searchFromDb(normalizedRequest)
            }
        } else {
            source = "db"
            searchFromDb(normalizedRequest)
        }

        cache.put(cacheKey, ttlMillis = 10_000, value = data)
        meterRegistry.timer("search_latency_ms", "source", source)
            .record(Duration.ofNanos(System.nanoTime() - startedAt))
        return data
    }

    private fun canUseOpenSearch(request: SearchRequest): Boolean {
        if (!useOpenSearch) {
            return false
        }
        return request.page == null &&
            request.size == null &&
            request.rooms == null &&
            request.children_ages.isEmpty() &&
            request.min_guest_rating == null &&
            request.min_location_rating == null &&
            request.max_distance_m == null &&
            request.nearby_attractions.isEmpty() &&
            request.guest_rating_bands.isEmpty() &&
            request.location_rating_bands.isEmpty() &&
            request.distance_bands.isEmpty() &&
            request.family_options.isEmpty() &&
            request.beach_options.isEmpty() &&
            request.stars.isEmpty() &&
            request.amenities.isEmpty() &&
            request.property_type.isEmpty() &&
            request.districts.isEmpty() &&
            request.payment_options.isEmpty() &&
            request.themes.isEmpty() &&
            request.brands.isEmpty() &&
            request.bed_types.isEmpty() &&
            request.bedrooms == null
    }

    private fun searchFromDb(request: SearchRequest): SearchData {
        val startedAt = System.nanoTime()
        val size = (request.size ?: request.limit).coerceIn(1, 50)
        val page = (request.page ?: 1).coerceAtLeast(1)
        val offset = (page - 1) * size
        val cursor = request.cursor?.toLongOrNull()

        val center = resolveCenter(request)
        val where = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        where += "p.status = 'ACTIVE'"

        if (request.property_id != null) {
            where += "p.id = ?"
            params += request.property_id
        } else if (!request.city.isNullOrBlank()) {
            where += "p.city = ?"
            params += request.city
        }
        if (!request.q.isNullOrBlank()) {
            where += "p.name LIKE ?"
            params += "%${request.q.trim()}%"
        }
        if (request.min_rating != null) {
            where += "COALESCE(p.rating, 0) >= ?"
            params += request.min_rating
        }
        if (request.min_guest_rating != null) {
            where += "COALESCE(p.rating, 0) >= ?"
            params += request.min_guest_rating
        }
        if (request.min_location_rating != null) {
            where += "COALESCE(p.location_rating, 0) >= ?"
            params += request.min_location_rating
        }
        if (request.guest_rating_bands.isNotEmpty()) {
            val threshold = minThreshold(request.guest_rating_bands)
            if (threshold != null) {
                where += "COALESCE(p.rating, 0) >= ?"
                params += threshold
            }
        }
        if (request.location_rating_bands.isNotEmpty()) {
            val threshold = minThreshold(request.location_rating_bands)
            if (threshold != null) {
                where += "COALESCE(p.location_rating, 0) >= ?"
                params += threshold
            }
        }
        if (request.stars.isNotEmpty()) {
            where += "COALESCE(p.star_rating, 0) IN (${request.stars.joinToString(",") { "?" }})"
            params.addAll(request.stars.map { it as Any? })
        }
        if (request.property_type.isNotEmpty()) {
            where += "COALESCE(p.property_type_code, '') IN (${request.property_type.joinToString(",") { "?" }})"
            params.addAll(request.property_type.map { it as Any? })
        }
        if (request.districts.isNotEmpty()) {
            where += "COALESCE(p.district_name, '') IN (${request.districts.joinToString(",") { "?" }})"
            params.addAll(request.districts.map { it as Any? })
        }
        if (request.min_price != null || request.max_price != null) {
            val pricePredicates = mutableListOf<String>()
            request.min_price?.let {
                pricePredicates += "rt2.base_price >= ?"
                params += it
            }
            request.max_price?.let {
                pricePredicates += "rt2.base_price <= ?"
                params += it
            }
            where += """
              EXISTS (
                SELECT 1
                FROM room_type rt2
                WHERE rt2.property_id = p.id
                  AND rt2.status = 'ACTIVE'
                  AND ${pricePredicates.joinToString(" AND ")}
              )
            """.trimIndent()
        }
        if (request.bed_types.isNotEmpty()) {
            where += """
              EXISTS (
                SELECT 1
                FROM room_type rt3
                WHERE rt3.property_id = p.id
                  AND rt3.status = 'ACTIVE'
                  AND COALESCE(rt3.bed_type, '') IN (${request.bed_types.joinToString(",") { "?" }})
              )
            """.trimIndent()
            params.addAll(request.bed_types.map { it as Any? })
        }
        if (request.bedrooms != null) {
            where += """
              EXISTS (
                SELECT 1
                FROM room_type rt4
                WHERE rt4.property_id = p.id
                  AND rt4.status = 'ACTIVE'
                  AND COALESCE(rt4.bedrooms, 1) >= ?
              )
            """.trimIndent()
            params += request.bedrooms
        }
        if (request.amenities.isNotEmpty()) {
            where += """
              EXISTS (
                SELECT 1
                FROM property_amenity pa
                WHERE pa.property_id = p.id
                  AND pa.amenity_code IN (${request.amenities.joinToString(",") { "?" }})
              )
            """.trimIndent()
            params.addAll(request.amenities.map { it as Any? })
        }
        if (request.themes.isNotEmpty()) {
            where += """
              EXISTS (
                SELECT 1
                FROM property_theme pt
                WHERE pt.property_id = p.id
                  AND pt.theme_code IN (${request.themes.joinToString(",") { "?" }})
              )
            """.trimIndent()
            params.addAll(request.themes.map { it as Any? })
        }
        if (request.payment_options.isNotEmpty()) {
            where += """
              EXISTS (
                SELECT 1
                FROM property_payment_option ppo
                WHERE ppo.property_id = p.id
                  AND ppo.payment_option_code IN (${request.payment_options.joinToString(",") { "?" }})
              )
            """.trimIndent()
            params.addAll(request.payment_options.map { it as Any? })
        }
        if (request.brands.isNotEmpty()) {
            val brandNames = request.brands.filter { it.toLongOrNull() == null }
            val brandIds = request.brands.mapNotNull { it.toLongOrNull() }
            val brandConditions = mutableListOf<String>()
            if (brandIds.isNotEmpty()) {
                brandConditions += "pb.brand_id IN (${brandIds.joinToString(",") { "?" }})"
                params.addAll(brandIds.map { it as Any? })
            }
            if (brandNames.isNotEmpty()) {
                brandConditions += "b.name IN (${brandNames.joinToString(",") { "?" }})"
                params.addAll(brandNames.map { it as Any? })
            }
            if (brandConditions.isNotEmpty()) {
                where += """
                  EXISTS (
                    SELECT 1
                    FROM property_brand pb
                    JOIN brand b ON b.id = pb.brand_id
                    WHERE pb.property_id = p.id
                      AND (${brandConditions.joinToString(" OR ")})
                  )
                """.trimIndent()
            }
        }

        if (request.family_options.any { it == "kid_free_stay" || it == "child_free_stay" }) {
            where += "COALESCE(p.kid_free_stay, 0) = 1"
        }
        if (request.beach_options.any { it == "beach_nearby" || it == "beachfront" }) {
            where += "(COALESCE(p.is_beachfront, 0) = 1 OR COALESCE(p.beach_distance_m, 999999) <= 1000)"
        }
        if (request.nearby_attractions.isNotEmpty()) {
            where += """
              EXISTS (
                SELECT 1
                FROM poi pfx
                WHERE pfx.id IN (${request.nearby_attractions.joinToString(",") { "?" }})
                  AND pfx.active = 1
                  AND pfx.city = p.city
                  AND (
                    6371000 * ACOS(
                      COS(RADIANS(COALESCE(p.lat, pfx.lat))) * COS(RADIANS(pfx.lat)) *
                      COS(RADIANS(COALESCE(p.lng, pfx.lng)) - RADIANS(pfx.lng)) +
                      SIN(RADIANS(COALESCE(p.lat, pfx.lat))) * SIN(RADIANS(pfx.lat))
                    )
                  ) <= 5000
              )
            """.trimIndent()
            params.addAll(request.nearby_attractions.map { it as Any? })
        }

        val distanceExpr = buildDistanceExpr(center)
        if (distanceExpr != null && request.distance_bands.isNotEmpty()) {
            where += "p.lat IS NOT NULL AND p.lng IS NOT NULL"
            val bandPredicates = mutableListOf<String>()
            request.distance_bands.forEach { band ->
                when (band) {
                    "center" -> {
                        bandPredicates += "$distanceExpr <= ?"
                        params += 1000
                    }

                    "under_2km" -> {
                        bandPredicates += "$distanceExpr <= ?"
                        params += 2000
                    }

                    "2_5km" -> {
                        bandPredicates += "($distanceExpr > ? AND $distanceExpr <= ?)"
                        params += 2000
                        params += 5000
                    }

                    "5_10km" -> {
                        bandPredicates += "($distanceExpr > ? AND $distanceExpr <= ?)"
                        params += 5000
                        params += 10000
                    }

                    "under_10km" -> {
                        bandPredicates += "$distanceExpr <= ?"
                        params += 10000
                    }
                }
            }
            if (bandPredicates.isNotEmpty()) {
                where += "(${bandPredicates.joinToString(" OR ")})"
            }
        }
        if (request.max_distance_m != null && distanceExpr != null) {
            where += "p.lat IS NOT NULL AND p.lng IS NOT NULL"
            where += "$distanceExpr <= ?"
            params += request.max_distance_m
        }
        if (cursor != null && request.page == null) {
            where += "p.id > ?"
            params += cursor
        }

        val whereClause = where.joinToString(" AND ")
        val order = when (request.sort) {
            "price_asc" -> "price_min ASC, p.id ASC"
            "price_desc" -> "price_min DESC, p.id DESC"
            "rating_desc" -> "rating DESC, p.id ASC"
            "distance", "distance_asc" -> if (distanceExpr != null) "$distanceExpr ASC, p.id ASC" else "p.id ASC"
            "best_match" -> "COALESCE(p.popularity_score, 0) DESC, rating DESC, p.id ASC"
            else -> "p.id ASC"
        }

        val selectDistance = if (distanceExpr != null) "$distanceExpr AS distance_m" else "NULL AS distance_m"
        val selectSql = """
            SELECT
              p.id,
              p.name,
              p.city,
              p.district_name,
              p.star_rating,
              COALESCE(MIN(rt.base_price), 0) AS price_min,
              COALESCE(p.rating, 0) AS rating,
              COALESCE(p.location_rating, 0) AS location_rating,
              COALESCE(p.review_count, 0) AS review_count,
              p.thumbnail_url,
              $selectDistance
            FROM property p
            LEFT JOIN room_type rt ON rt.property_id = p.id AND rt.status = 'ACTIVE'
            WHERE $whereClause
            GROUP BY p.id, p.name, p.city, p.district_name, p.star_rating, p.rating, p.location_rating, p.review_count, p.thumbnail_url, p.lat, p.lng
            ORDER BY $order
            LIMIT ?
        """.trimIndent()

        val selectParams = mutableListOf<Any?>().apply {
            addAll(params)
            add(if (request.page == null && cursor != null) size + 1 else size)
            if (request.page != null) {
                add(offset)
            }
        }

        val finalSelectSql = if (request.page != null) "$selectSql OFFSET ?" else selectSql
        val rows = jdbcTemplate.query(
            finalSelectSql,
            { rs, _ ->
                SearchItem(
                    property_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    city = rs.getString("city"),
                    district = rs.getString("district_name"),
                    price_min = rs.getLong("price_min"),
                    rating = rs.getBigDecimal("rating")?.toDouble() ?: 0.0,
                    location_rating = rs.getBigDecimal("location_rating")?.toDouble() ?: 0.0,
                    star_rating = rs.getInt("star_rating"),
                    review_count = rs.getInt("review_count"),
                    thumbnail_url = rs.getString("thumbnail_url"),
                    distance_m = rs.getDouble("distance_m").takeIf { !rs.wasNull() }?.toInt(),
                    currency = request.currency,
                )
            },
            *selectParams.toTypedArray(),
        )

        val hasNext = request.page == null && cursor != null && rows.size > size
        val pagedItems = if (hasNext) rows.dropLast(1) else rows
        val convertedItems = pagedItems.map { item ->
            item.copy(
                price_min = fxService.convert(item.price_min, "KRW", request.currency),
                currency = request.currency,
            )
        }

        val countSql = """
            SELECT COUNT(*)
            FROM property p
            WHERE $whereClause
        """.trimIndent()
        val total = jdbcTemplate.queryForObject(countSql, Long::class.java, *params.toTypedArray()) ?: convertedItems.size.toLong()

        val data = SearchData(
            items = convertedItems,
            next_cursor = if (hasNext) pagedItems.last().property_id.toString() else null,
            facets = searchFacetService.facets(request.place_id, request.city),
            meta = SearchMeta(
                total = total,
                took_ms = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1),
                page = request.page ?: 1,
                size = size,
                currency = request.currency,
            ),
        )
        return data
    }

    private fun buildDistanceExpr(center: LatLng?): String? {
        if (center == null) return null
        val lat = center.lat
        val lng = center.lng
        return """
          (
            6371000 * ACOS(
              COS(RADIANS($lat)) * COS(RADIANS(p.lat)) *
              COS(RADIANS(p.lng) - RADIANS($lng)) +
              SIN(RADIANS($lat)) * SIN(RADIANS(p.lat))
            )
          )
        """.trimIndent()
    }

    private fun resolveCenter(request: SearchRequest): LatLng? {
        val placeId = PlaceIdCodec.parseOrNull(request.place_id)
        if (placeId != null) {
            return when (placeId.type) {
                PlaceType.PROPERTY -> {
                    val propertyId = placeId.canonicalId.toLongOrNull() ?: return null
                    jdbcTemplate.query(
                        "SELECT lat, lng FROM property WHERE id = ? LIMIT 1",
                        { rs, _ -> LatLng(rs.getDouble("lat"), rs.getDouble("lng")) },
                        propertyId,
                    ).firstOrNull()
                }

                PlaceType.POI -> {
                    val poiId = placeId.canonicalId.toLongOrNull() ?: return null
                    jdbcTemplate.query(
                        "SELECT lat, lng FROM poi WHERE id = ? LIMIT 1",
                        { rs, _ -> LatLng(rs.getDouble("lat"), rs.getDouble("lng")) },
                        poiId,
                    ).firstOrNull()
                }

                PlaceType.CITY -> cityCenter(placeId.canonicalId)
                PlaceType.STATION,
                PlaceType.AIRPORT,
                -> null
            }
        }
        return request.city?.let { cityCenter(it) }
    }

    private fun cityCenter(city: String): LatLng? {
        return jdbcTemplate.query(
            """
            SELECT AVG(lat) AS lat, AVG(lng) AS lng
            FROM property
            WHERE city = ?
              AND status = 'ACTIVE'
              AND lat IS NOT NULL
              AND lng IS NOT NULL
            """.trimIndent(),
            { rs, _ ->
                val lat = rs.getDouble("lat")
                val lng = rs.getDouble("lng")
                if (rs.wasNull()) null else LatLng(lat, lng)
            },
            city,
        ).firstOrNull()
    }

    private fun enrichSearchData(
        openSearchData: SearchData,
        request: SearchRequest,
        startedAt: Long,
    ): SearchData {
        val size = (request.size ?: request.limit).coerceIn(1, 50)
        val converted = openSearchData.items.map { item ->
            item.copy(
                price_min = fxService.convert(item.price_min, "KRW", request.currency),
                currency = request.currency,
            )
        }
        return openSearchData.copy(
            items = converted,
            facets = searchFacetService.facets(request.place_id, request.city),
            meta = SearchMeta(
                total = converted.size.toLong(),
                took_ms = Duration.ofNanos(System.nanoTime() - startedAt).toMillis().coerceAtLeast(1),
                page = request.page ?: 1,
                size = size,
                currency = request.currency,
            ),
        )
    }

    private fun normalized(request: SearchRequest): String {
        return listOf(
            "q=${request.q ?: ""}",
            "city=${request.city ?: ""}",
            "place_id=${request.place_id ?: ""}",
            "property_id=${request.property_id ?: ""}",
            "check_in=${request.check_in ?: ""}",
            "check_out=${request.check_out ?: ""}",
            "rooms=${request.rooms ?: ""}",
            "adults=${request.adults ?: ""}",
            "children=${request.children ?: ""}",
            "children_ages=${request.children_ages.joinToString(",")}",
            "currency=${request.currency}",
            "min_price=${request.min_price ?: ""}",
            "max_price=${request.max_price ?: ""}",
            "min_rating=${request.min_rating ?: ""}",
            "min_guest_rating=${request.min_guest_rating ?: ""}",
            "min_location_rating=${request.min_location_rating ?: ""}",
            "max_distance_m=${request.max_distance_m ?: ""}",
            "stars=${request.stars.joinToString(",")}",
            "amenities=${request.amenities.joinToString(",")}",
            "property_type=${request.property_type.joinToString(",")}",
            "districts=${request.districts.joinToString(",")}",
            "payment_options=${request.payment_options.joinToString(",")}",
            "themes=${request.themes.joinToString(",")}",
            "brands=${request.brands.joinToString(",")}",
            "bed_types=${request.bed_types.joinToString(",")}",
            "bedrooms=${request.bedrooms ?: ""}",
            "nearby_attractions=${request.nearby_attractions.joinToString(",")}",
            "guest_rating_bands=${request.guest_rating_bands.joinToString(",")}",
            "location_rating_bands=${request.location_rating_bands.joinToString(",")}",
            "distance_bands=${request.distance_bands.joinToString(",")}",
            "family_options=${request.family_options.joinToString(",")}",
            "beach_options=${request.beach_options.joinToString(",")}",
            "sort=${request.sort ?: ""}",
            "page=${request.page ?: ""}",
            "size=${request.size ?: ""}",
            "cursor=${request.cursor ?: ""}",
            "limit=${request.limit}",
        ).joinToString("&")
    }

    private fun recordFilterUsage(request: SearchRequest) {
        val keys = activeFilterKeys(request)
        meterRegistry.summary("search_active_filter_count").record(keys.size.toDouble())
        keys.forEach { key ->
            meterRegistry.counter("search_filter_usage_total", "filter", key).increment()
        }
    }

    private fun activeFilterKeys(request: SearchRequest): List<String> {
        val keys = mutableListOf<String>()
        if (request.min_price != null || request.max_price != null) keys += "price_range"
        if (request.min_rating != null) keys += "min_rating"
        if (request.min_guest_rating != null) keys += "min_guest_rating"
        if (request.min_location_rating != null) keys += "min_location_rating"
        if (request.max_distance_m != null) keys += "max_distance_m"
        if (request.stars.isNotEmpty()) keys += "stars"
        if (request.amenities.isNotEmpty()) keys += "amenities"
        if (request.property_type.isNotEmpty()) keys += "property_type"
        if (request.districts.isNotEmpty()) keys += "districts"
        if (request.payment_options.isNotEmpty()) keys += "payment_options"
        if (request.themes.isNotEmpty()) keys += "themes"
        if (request.brands.isNotEmpty()) keys += "brands"
        if (request.bed_types.isNotEmpty()) keys += "bed_types"
        if (request.bedrooms != null) keys += "bedrooms"
        if (request.nearby_attractions.isNotEmpty()) keys += "nearby_attractions"
        if (request.guest_rating_bands.isNotEmpty()) keys += "guest_rating_bands"
        if (request.location_rating_bands.isNotEmpty()) keys += "location_rating_bands"
        if (request.distance_bands.isNotEmpty()) keys += "distance_bands"
        if (request.family_options.isNotEmpty()) keys += "family_options"
        if (request.beach_options.isNotEmpty()) keys += "beach_options"
        if (!request.sort.isNullOrBlank()) keys += "sort"
        return keys
    }

    private fun minThreshold(bands: List<String>): Double? {
        val values = bands.mapNotNull { band ->
            when (band.trim().lowercase(Locale.ROOT)) {
                "9_plus", "9plus", "9+" -> 4.5
                "8_plus", "8plus", "8+" -> 4.0
                "7_plus", "7plus", "7+" -> 3.5
                "6_plus", "6plus", "6+" -> 3.0
                else -> band.toDoubleOrNull()
            }
        }
        return values.minOrNull()
    }

    private fun sha256(value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun resolvePlaceFilter(request: SearchRequest): SearchRequest {
        val placeId = PlaceIdCodec.parseOrNull(request.place_id) ?: return request
        return when (placeId.type) {
            PlaceType.CITY -> request.copy(
                city = placeId.canonicalId,
                property_id = null,
            )

            PlaceType.PROPERTY -> {
                val propertyId = placeId.canonicalId.toLongOrNull()
                    ?: throw DomainException(
                        errorCode = ErrorCode.VALIDATION_ERROR,
                        message = "property place_id must have numeric canonical_id",
                        details = mapOf("place_id" to request.place_id),
                    )
                request.copy(
                    city = null,
                    property_id = propertyId,
                )
            }

            PlaceType.POI -> request.copy(
                city = resolvePoiCity(placeId.canonicalId) ?: request.city,
                property_id = null,
            )

            PlaceType.STATION,
            PlaceType.AIRPORT,
            -> request
        }
    }

    private fun resolvePoiCity(canonicalId: String): String? {
        val poiId = canonicalId.toLongOrNull() ?: return null
        return jdbcTemplate.query(
            """
            SELECT city
            FROM poi
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getString("city") },
            poiId,
        ).firstOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}

data class SearchRequest(
    val q: String?,
    val city: String?,
    val place_id: String? = null,
    val check_in: String?,
    val check_out: String?,
    val adults: Int?,
    val children: Int?,
    val min_price: Long?,
    val max_price: Long?,
    val min_rating: Double?,
    val sort: String?,
    val cursor: String?,
    val limit: Int,
    val property_id: Long? = null,
    val rooms: Int? = null,
    val children_ages: List<Int> = emptyList(),
    val currency: String = "KRW",
    val min_guest_rating: Double? = null,
    val min_location_rating: Double? = null,
    val max_distance_m: Int? = null,
    val stars: List<Int> = emptyList(),
    val amenities: List<String> = emptyList(),
    val property_type: List<String> = emptyList(),
    val districts: List<String> = emptyList(),
    val payment_options: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    val brands: List<String> = emptyList(),
    val bed_types: List<String> = emptyList(),
    val bedrooms: Int? = null,
    val nearby_attractions: List<Long> = emptyList(),
    val guest_rating_bands: List<String> = emptyList(),
    val location_rating_bands: List<String> = emptyList(),
    val distance_bands: List<String> = emptyList(),
    val family_options: List<String> = emptyList(),
    val beach_options: List<String> = emptyList(),
    val page: Int? = null,
    val size: Int? = null,
) {
    fun normalize(): SearchRequest {
        return copy(
            q = q?.trim()?.takeIf { it.isNotBlank() },
            city = city?.trim()?.takeIf { it.isNotBlank() },
            place_id = place_id?.trim()?.takeIf { it.isNotBlank() },
            adults = adults?.coerceIn(1, 16),
            children = children?.coerceIn(0, 8),
            rooms = rooms?.coerceIn(1, 8),
            children_ages = children_ages.map { it.coerceIn(0, 17) },
            currency = currency.trim().uppercase(Locale.ROOT).ifBlank { "KRW" },
            stars = stars.map { it.coerceIn(1, 5) }.distinct().sorted(),
            amenities = amenities.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            property_type = property_type.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            districts = districts.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            payment_options = payment_options.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            themes = themes.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            brands = brands.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            bed_types = bed_types.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct(),
            bedrooms = bedrooms?.coerceIn(1, 8),
            nearby_attractions = nearby_attractions.distinct().sorted(),
            guest_rating_bands = guest_rating_bands.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().sorted(),
            location_rating_bands = location_rating_bands.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().sorted(),
            distance_bands = distance_bands.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().sorted(),
            family_options = family_options.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().sorted(),
            beach_options = beach_options.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().sorted(),
            page = page?.coerceAtLeast(1),
            size = size?.coerceIn(1, 50),
            limit = limit.coerceIn(1, 50),
        )
    }
}

data class SearchItem(
    val property_id: Long,
    val name: String,
    val city: String?,
    val district: String? = null,
    val price_min: Long,
    val rating: Double,
    val location_rating: Double = 0.0,
    val star_rating: Int = 0,
    val review_count: Int = 0,
    val thumbnail_url: String?,
    val distance_m: Int? = null,
    val currency: String = "KRW",
)

data class SearchData(
    val items: List<SearchItem>,
    val next_cursor: String? = null,
    val facets: SearchFacetData? = null,
    val meta: SearchMeta? = null,
)

data class SearchMeta(
    val total: Long,
    val took_ms: Long,
    val page: Int,
    val size: Int,
    val currency: String,
)

private data class LatLng(
    val lat: Double,
    val lng: Double,
)
