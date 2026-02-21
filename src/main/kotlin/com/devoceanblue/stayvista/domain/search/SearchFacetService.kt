package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SearchFacetService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun facets(placeId: String?, city: String?): SearchFacetData {
        val resolvedCity = resolveCity(placeId, city)
        return SearchFacetData(
            popular_filters = safeList { popularFilters(resolvedCity) },
            districts = safeList { districtFacets(resolvedCity) },
            nearby_attractions = safeList { nearbyAttractions(resolvedCity) },
            brands = safeList { brandFacets(resolvedCity) },
            amenity_groups = safeList { amenityGroups(resolvedCity) },
            stars = safeList { starFacets(resolvedCity) },
            property_types = safeList { propertyTypeFacets(resolvedCity) },
            payment_options = safeList { paymentOptionFacets(resolvedCity) },
            themes = safeList { themeFacets(resolvedCity) },
            amenities = safeList { amenityFacets(resolvedCity) },
            guest_rating_bands = safeList { guestRatingBands(resolvedCity) },
            location_rating_bands = safeList { locationRatingBands(resolvedCity) },
            distance_bands = safeList { distanceBands(resolvedCity) },
            bed_types = safeList { bedTypeFacets(resolvedCity) },
            bedrooms = safeList { bedroomFacets(resolvedCity) },
            family_options = safeList { familyOptions(resolvedCity) },
            beach_options = safeList { beachOptions(resolvedCity) },
        )
    }

    private fun <T> safeList(block: () -> List<T>): List<T> {
        return runCatching { block() }.getOrDefault(emptyList())
    }

    private fun resolveCity(placeId: String?, city: String?): String? {
        val plainCity = city?.trim()?.takeIf { it.isNotBlank() }
        if (plainCity != null) {
            return plainCity
        }

        if (!placeId.isNullOrBlank() && !placeId.contains(':')) {
            return placeId.trim()
        }
        val parsed = PlaceIdCodec.parseOrNull(placeId) ?: return null
        return when (parsed.type) {
            PlaceType.CITY -> parsed.canonicalId
            PlaceType.POI -> {
                val poiId = parsed.canonicalId.toLongOrNull() ?: return null
                jdbcTemplate.query(
                    "SELECT city FROM poi WHERE id = ? LIMIT 1",
                    { rs, _ -> rs.getString("city") },
                    poiId,
                ).firstOrNull()
            }

            else -> null
        }
    }

    private fun popularFilters(city: String?): List<PopularFilterFacet> {
        val amenityMap = amenityFacets(city)
            .associateBy { it.key }
        val popularAmenityCodes = listOf("fridge", "air_conditioning", "tv", "heating", "parking", "internet")

        val fromAmenities = popularAmenityCodes.mapNotNull { code ->
            val amenity = amenityMap[code] ?: return@mapNotNull null
            PopularFilterFacet(
                key = "amenities",
                value = amenity.key,
                label = amenity.label,
                count = amenity.count,
            )
        }

        val guestBand = guestRatingBands(city).firstOrNull { it.key == "8_plus" }
        val locationBand = locationRatingBands(city).firstOrNull { it.key == "8_plus" }

        val extras = mutableListOf<PopularFilterFacet>()
        if (locationBand != null) {
            extras += PopularFilterFacet(
                key = "location_rating_bands",
                value = locationBand.key,
                label = "위치: 8+ 우수",
                count = locationBand.count,
            )
        }
        if (guestBand != null) {
            extras += PopularFilterFacet(
                key = "guest_rating_bands",
                value = guestBand.key,
                label = "투숙객 평점: 8+ 우수",
                count = guestBand.count,
            )
        }

        return (fromAmenities + extras).take(10)
    }

    private fun districtFacets(city: String?): List<FacetDistrict> {
        val whereCity = if (city != null) "WHERE d.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT d.id, d.name, d.blurb, d.rank_score, COALESCE(p.cnt, 0) AS cnt
            FROM district d
            LEFT JOIN (
              SELECT district_name, city, COUNT(*) AS cnt
              FROM property
              WHERE status = 'ACTIVE'
              GROUP BY district_name, city
            ) p ON p.district_name = d.name AND p.city = d.city
            $whereCity
            ORDER BY d.rank_score DESC, cnt DESC, d.id ASC
            LIMIT 16
            """.trimIndent(),
            { rs, _ ->
                FacetDistrict(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    blurb = rs.getString("blurb"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun nearbyAttractions(city: String?): List<FacetAttraction> {
        if (city == null) {
            return emptyList()
        }
        return jdbcTemplate.query(
            """
            SELECT p.id, p.name, cpp.rank_score
            FROM city_poi_popular cpp
            JOIN poi p ON p.id = cpp.poi_id
            WHERE cpp.city = ?
            ORDER BY cpp.rank_score DESC, p.id ASC
            LIMIT 16
            """.trimIndent(),
            { rs, _ ->
                FacetAttraction(
                    poi_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    count = rs.getInt("rank_score"),
                )
            },
            city,
        )
    }

    private fun brandFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT b.id, b.name, COUNT(*) AS cnt
            FROM property_brand pb
            JOIN brand b ON b.id = pb.brand_id
            JOIN property p ON p.id = pb.property_id
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY b.id, b.name
            ORDER BY cnt DESC, b.name ASC
            LIMIT 24
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getLong("id").toString(),
                    label = rs.getString("name"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun amenityGroups(city: String?): List<AmenityGroupFacet> {
        val order = mapOf(
            "popular_condition" to 0,
            "service_option" to 1,
            "property_facility" to 2,
            "room_facility" to 3,
        )
        val byAmenity = amenityFacets(city)
            .groupBy { it.group ?: "other" }
        return byAmenity.entries.map { (group, items) ->
            AmenityGroupFacet(
                group = group,
                items = items.sortedByDescending { it.count },
            )
        }.sortedWith(
            compareBy<AmenityGroupFacet> { order[it.group] ?: 99 }
                .thenBy { it.group },
        )
    }

    private fun starFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT star_rating, COUNT(*) AS cnt
            FROM property
            WHERE status = 'ACTIVE'
            $whereCity
            GROUP BY star_rating
            ORDER BY star_rating DESC
            """.trimIndent(),
            { rs, _ ->
                val star = rs.getInt("star_rating")
                FacetCountItem(
                    key = star.toString(),
                    label = "${star}성급",
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun propertyTypeFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT pt.code, pt.label_ko, COUNT(*) AS cnt
            FROM property p
            JOIN property_type pt ON pt.code = p.property_type_code
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY pt.code, pt.label_ko
            ORDER BY cnt DESC, pt.code ASC
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getString("code"),
                    label = rs.getString("label_ko"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun paymentOptionFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT po.code, po.label_ko, COUNT(*) AS cnt
            FROM property_payment_option ppo
            JOIN payment_option po ON po.code = ppo.payment_option_code
            JOIN property p ON p.id = ppo.property_id
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY po.code, po.label_ko
            ORDER BY cnt DESC, po.code ASC
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getString("code"),
                    label = rs.getString("label_ko"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun themeFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT t.code, t.label_ko, COUNT(*) AS cnt
            FROM property_theme pt
            JOIN theme t ON t.code = pt.theme_code
            JOIN property p ON p.id = pt.property_id
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY t.code, t.label_ko
            ORDER BY cnt DESC, t.code ASC
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getString("code"),
                    label = rs.getString("label_ko"),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun amenityFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT a.code, a.label_ko, a.group_code, COUNT(*) AS cnt
            FROM property_amenity pa
            JOIN amenity a ON a.code = pa.amenity_code
            JOIN property p ON p.id = pa.property_id
            WHERE p.status = 'ACTIVE'
            $whereCity
            GROUP BY a.code, a.label_ko, a.group_code
            ORDER BY cnt DESC, a.code ASC
            """.trimIndent(),
            { rs, _ ->
                FacetCountItem(
                    key = rs.getString("code"),
                    label = rs.getString("label_ko"),
                    count = rs.getInt("cnt"),
                    group = rs.getString("group_code"),
                )
            },
            *params,
        )
    }

    private fun guestRatingBands(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        val row = jdbcTemplate.query(
            """
            SELECT
              SUM(CASE WHEN COALESCE(rating, 0) >= 4.5 THEN 1 ELSE 0 END) AS g9,
              SUM(CASE WHEN COALESCE(rating, 0) >= 4.0 THEN 1 ELSE 0 END) AS g8,
              SUM(CASE WHEN COALESCE(rating, 0) >= 3.5 THEN 1 ELSE 0 END) AS g7,
              SUM(CASE WHEN COALESCE(rating, 0) >= 3.0 THEN 1 ELSE 0 END) AS g6
            FROM property
            WHERE status = 'ACTIVE'
            $whereCity
            """.trimIndent(),
            { rs, _ ->
                listOf(
                    FacetCountItem("9_plus", "9+ 최고", rs.getInt("g9")),
                    FacetCountItem("8_plus", "8+ 우수", rs.getInt("g8")),
                    FacetCountItem("7_plus", "7+ 좋음", rs.getInt("g7")),
                    FacetCountItem("6_plus", "6+ 양호", rs.getInt("g6")),
                )
            },
            *params,
        ).firstOrNull()
        return row?.filter { it.count > 0 } ?: emptyList()
    }

    private fun locationRatingBands(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        val row = jdbcTemplate.query(
            """
            SELECT
              SUM(CASE WHEN COALESCE(location_rating, 0) >= 4.5 THEN 1 ELSE 0 END) AS g9,
              SUM(CASE WHEN COALESCE(location_rating, 0) >= 4.0 THEN 1 ELSE 0 END) AS g8,
              SUM(CASE WHEN COALESCE(location_rating, 0) >= 3.5 THEN 1 ELSE 0 END) AS g7,
              SUM(CASE WHEN COALESCE(location_rating, 0) >= 3.0 THEN 1 ELSE 0 END) AS g6
            FROM property
            WHERE status = 'ACTIVE'
            $whereCity
            """.trimIndent(),
            { rs, _ ->
                listOf(
                    FacetCountItem("9_plus", "9+ 최고", rs.getInt("g9")),
                    FacetCountItem("8_plus", "8+ 우수", rs.getInt("g8")),
                    FacetCountItem("7_plus", "7+ 좋음", rs.getInt("g7")),
                    FacetCountItem("6_plus", "6+ 양호", rs.getInt("g6")),
                )
            },
            *params,
        ).firstOrNull()
        return row?.filter { it.count > 0 } ?: emptyList()
    }

    private fun distanceBands(city: String?): List<FacetCountItem> {
        if (city == null) {
            return emptyList()
        }
        val center = cityCenter(city) ?: return emptyList()
        val distanceExpr = buildDistanceExpr(center)
        val row = jdbcTemplate.query(
            """
            SELECT
              SUM(CASE WHEN dist <= 1000 THEN 1 ELSE 0 END) AS center_cnt,
              SUM(CASE WHEN dist <= 2000 THEN 1 ELSE 0 END) AS under_2km_cnt,
              SUM(CASE WHEN dist > 2000 AND dist <= 5000 THEN 1 ELSE 0 END) AS between_2_5_cnt,
              SUM(CASE WHEN dist > 5000 AND dist <= 10000 THEN 1 ELSE 0 END) AS between_5_10_cnt,
              SUM(CASE WHEN dist <= 10000 THEN 1 ELSE 0 END) AS under_10km_cnt
            FROM (
              SELECT $distanceExpr AS dist
              FROM property p
              WHERE p.status = 'ACTIVE'
                AND p.city = ?
                AND p.lat IS NOT NULL
                AND p.lng IS NOT NULL
            ) scoped
            """.trimIndent(),
            { rs, _ ->
                listOf(
                    FacetCountItem("center", "도심에 위치", rs.getInt("center_cnt")),
                    FacetCountItem("under_2km", "도심까지 2km 미만", rs.getInt("under_2km_cnt")),
                    FacetCountItem("2_5km", "도심까지 2~5km", rs.getInt("between_2_5_cnt")),
                    FacetCountItem("5_10km", "도심까지 5~10km", rs.getInt("between_5_10_cnt")),
                    FacetCountItem("under_10km", "도심까지 10km 미만", rs.getInt("under_10km_cnt")),
                )
            },
            city,
        ).firstOrNull()
        return row?.filter { it.count > 0 } ?: emptyList()
    }

    private fun bedTypeFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT UPPER(rt.bed_type) AS bed_type, COUNT(*) AS cnt
            FROM room_type rt
            JOIN property p ON p.id = rt.property_id
            WHERE p.status = 'ACTIVE'
              AND rt.status = 'ACTIVE'
              AND rt.bed_type IS NOT NULL
              AND rt.bed_type <> ''
            $whereCity
            GROUP BY UPPER(rt.bed_type)
            ORDER BY cnt DESC, bed_type ASC
            LIMIT 8
            """.trimIndent(),
            { rs, _ ->
                val key = rs.getString("bed_type")
                FacetCountItem(
                    key = key,
                    label = bedTypeLabel(key),
                    count = rs.getInt("cnt"),
                )
            },
            *params,
        )
    }

    private fun bedroomFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        val row = jdbcTemplate.query(
            """
            SELECT
              COUNT(DISTINCT CASE WHEN COALESCE(rt.bedrooms, 1) = 1 THEN p.id END) AS b1,
              COUNT(DISTINCT CASE WHEN COALESCE(rt.bedrooms, 1) = 2 THEN p.id END) AS b2,
              COUNT(DISTINCT CASE WHEN COALESCE(rt.bedrooms, 1) >= 3 THEN p.id END) AS b3
            FROM property p
            JOIN room_type rt ON rt.property_id = p.id
            WHERE p.status = 'ACTIVE'
              AND rt.status = 'ACTIVE'
            $whereCity
            """.trimIndent(),
            { rs, _ ->
                listOf(
                    FacetCountItem("1", "스튜디오 / 침실 1개", rs.getInt("b1")),
                    FacetCountItem("2", "침실 2개", rs.getInt("b2")),
                    FacetCountItem("3", "침실 3+개", rs.getInt("b3")),
                )
            },
            *params,
        ).firstOrNull()
        return row?.filter { it.count > 0 } ?: emptyList()
    }

    private fun familyOptions(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        val count = jdbcTemplate.query(
            """
            SELECT COUNT(*) AS cnt
            FROM property
            WHERE status = 'ACTIVE'
              AND COALESCE(kid_free_stay, 0) = 1
            $whereCity
            """.trimIndent(),
            { rs, _ -> rs.getInt("cnt") },
            *params,
        ).firstOrNull() ?: 0
        if (count <= 0) {
            return emptyList()
        }
        return listOf(FacetCountItem("kid_free_stay", "아동 무료 투숙 가능", count))
    }

    private fun beachOptions(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        val count = jdbcTemplate.query(
            """
            SELECT COUNT(*) AS cnt
            FROM property
            WHERE status = 'ACTIVE'
              AND (COALESCE(is_beachfront, 0) = 1 OR COALESCE(beach_distance_m, 999999) <= 1000)
            $whereCity
            """.trimIndent(),
            { rs, _ -> rs.getInt("cnt") },
            *params,
        ).firstOrNull() ?: 0
        if (count <= 0) {
            return emptyList()
        }
        return listOf(FacetCountItem("beach_nearby", "전용 해변", count))
    }

    private fun bedTypeLabel(code: String): String {
        return when (code.uppercase()) {
            "DOUBLE" -> "더블베드"
            "TWIN", "SINGLE" -> "싱글/트윈베드"
            "QUEEN" -> "퀸베드"
            "KING" -> "킹베드"
            "BUNK" -> "벙크베드"
            else -> code
        }
    }

    private fun cityCenter(city: String): FacetLatLng? {
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
                if (rs.wasNull()) null else FacetLatLng(lat, lng)
            },
            city,
        ).firstOrNull()
    }

    private fun buildDistanceExpr(center: FacetLatLng): String {
        return """
          (
            6371000 * ACOS(
              COS(RADIANS(${center.lat})) * COS(RADIANS(p.lat)) *
              COS(RADIANS(p.lng) - RADIANS(${center.lng})) +
              SIN(RADIANS(${center.lat})) * SIN(RADIANS(p.lat))
            )
          )
        """.trimIndent()
    }
}

data class SearchFacetData(
    val popular_filters: List<PopularFilterFacet>,
    val districts: List<FacetDistrict>,
    val nearby_attractions: List<FacetAttraction>,
    val brands: List<FacetCountItem>,
    val amenity_groups: List<AmenityGroupFacet>,
    val stars: List<FacetCountItem>,
    val property_types: List<FacetCountItem>,
    val payment_options: List<FacetCountItem>,
    val themes: List<FacetCountItem>,
    val amenities: List<FacetCountItem>,
    val guest_rating_bands: List<FacetCountItem>,
    val location_rating_bands: List<FacetCountItem>,
    val distance_bands: List<FacetCountItem>,
    val bed_types: List<FacetCountItem>,
    val bedrooms: List<FacetCountItem>,
    val family_options: List<FacetCountItem>,
    val beach_options: List<FacetCountItem>,
)

data class PopularFilterFacet(
    val key: String,
    val value: String,
    val label: String,
    val count: Int,
)

data class FacetDistrict(
    val id: Long,
    val name: String,
    val blurb: String?,
    val count: Int,
)

data class FacetAttraction(
    val poi_id: Long,
    val name: String,
    val count: Int,
)

data class AmenityGroupFacet(
    val group: String,
    val items: List<FacetCountItem>,
)

data class FacetCountItem(
    val key: String,
    val label: String,
    val count: Int,
    val group: String? = null,
)

private data class FacetLatLng(
    val lat: Double,
    val lng: Double,
)
