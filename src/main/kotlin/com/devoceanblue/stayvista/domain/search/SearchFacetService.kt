package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SearchFacetService(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    fun facets(placeId: String?, city: String?): SearchFacetData {
        val startedAt = System.nanoTime()
        val resolvedCity = resolveCity(placeId, city)
        val result = SearchFacetData(
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
        val emptyGroups = listOf(
            result.popular_filters.isEmpty(),
            result.districts.isEmpty(),
            result.nearby_attractions.isEmpty(),
            result.brands.isEmpty(),
            result.amenity_groups.isEmpty(),
            result.stars.isEmpty(),
            result.property_types.isEmpty(),
            result.payment_options.isEmpty(),
            result.themes.isEmpty(),
            result.amenities.isEmpty(),
            result.guest_rating_bands.isEmpty(),
            result.location_rating_bands.isEmpty(),
            result.distance_bands.isEmpty(),
            result.bed_types.isEmpty(),
            result.bedrooms.isEmpty(),
            result.family_options.isEmpty(),
            result.beach_options.isEmpty(),
        ).count { it }
        meterRegistry.counter(
            "search_facets_requests_total",
            "scope",
            if (resolvedCity == null) "global" else "city",
        ).increment()
        meterRegistry.summary("search_facets_empty_group_count").record(emptyGroups.toDouble())
        meterRegistry.timer("search_facets_latency_ms")
            .record(Duration.ofNanos(System.nanoTime() - startedAt))
        return result
    }

    private fun <T> safeList(block: () -> List<T>): List<T> {
        return runCatching { block() }.getOrDefault(emptyList())
    }

    private fun resolveCity(placeId: String?, city: String?): String? {
        val plainCity = CityCanonicalizer.canonicalize(city)
        if (plainCity != null) {
            return plainCity
        }

        if (!placeId.isNullOrBlank() && !placeId.contains(':')) {
            return CityCanonicalizer.canonicalize(placeId)
        }
        val parsed = PlaceIdCodec.parseOrNull(placeId) ?: return null
        return when (parsed.type) {
            PlaceType.CITY -> CityCanonicalizer.canonicalize(parsed.canonicalId)
            PlaceType.POI -> {
                val poiId = parsed.canonicalId.toLongOrNull() ?: return null
                jdbcTemplate.query(
                    "SELECT city FROM poi WHERE id = ? LIMIT 1",
                    { rs, _ -> CityCanonicalizer.canonicalize(rs.getString("city")) },
                    poiId,
                ).firstOrNull()
            }

            else -> null
        }
    }

    private fun popularFilters(city: String?): List<PopularFilterFacet> {
        val amenityMap = amenityFacets(city).associateBy { it.key }
        val amenityOrder = listOf("fridge", "air_conditioning", "tv", "heating", "parking", "internet")
        val defaults = mapOf(
            "fridge" to "냉장고",
            "air_conditioning" to "에어컨",
            "tv" to "TV",
            "heating" to "난방",
            "parking" to "주차장",
            "internet" to "인터넷",
        )

        val fromAmenities = amenityOrder.map { code ->
            val amenity = amenityMap[code]
            PopularFilterFacet(
                key = "amenities",
                value = code,
                label = amenity?.label ?: defaults[code] ?: code,
                count = amenity?.count ?: 0,
            )
        }

        val guestBand = guestRatingBands(city).firstOrNull { it.key == "8_plus" } ?: FacetCountItem("8_plus", "8+ 우수", 0)
        val locationBand = locationRatingBands(city).firstOrNull { it.key == "8_plus" } ?: FacetCountItem("8_plus", "8+ 우수", 0)

        val extras = listOf(
            PopularFilterFacet(
                key = "location_rating_bands",
                value = locationBand.key,
                label = "위치: 8+ 우수",
                count = locationBand.count,
            ),
            PopularFilterFacet(
                key = "guest_rating_bands",
                value = guestBand.key,
                label = "투숙객 평점: 8+ 우수",
                count = guestBand.count,
            ),
        )

        return (fromAmenities + extras).take(10)
    }

    private fun districtFacets(city: String?): List<FacetDistrict> {
        val whereCity = if (city != null) "WHERE d.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        val fromDistrict = jdbcTemplate.query(
            """
            SELECT d.id, d.name, d.blurb, d.rank_score, COALESCE(p.cnt, 0) AS cnt
            FROM district d
            LEFT JOIN (
              SELECT district_name, city, COUNT(*) AS cnt
              FROM property
              WHERE status = 'ACTIVE'
                AND district_name IS NOT NULL
                AND district_name <> ''
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
        if (fromDistrict.isNotEmpty()) {
            return fromDistrict
        }

        val whereFallbackCity = if (city != null) "AND city = ?" else ""
        val fallbackParams = if (city != null) arrayOf<Any>(city) else emptyArray()
        val fromProperty = jdbcTemplate.query(
            """
            SELECT district_name, COUNT(*) AS cnt
            FROM property
            WHERE status = 'ACTIVE'
              AND district_name IS NOT NULL
              AND district_name <> ''
              $whereFallbackCity
            GROUP BY district_name
            ORDER BY cnt DESC, district_name ASC
            LIMIT 16
            """.trimIndent(),
            { rs, _ ->
                Pair(rs.getString("district_name"), rs.getInt("cnt"))
            },
            *fallbackParams,
        )
        return fromProperty.mapIndexed { index, (name, count) ->
            FacetDistrict(
                id = (index + 1).toLong(),
                name = name,
                blurb = defaultDistrictBlurb(city, name),
                count = count,
            )
        }
    }

    private fun nearbyAttractions(city: String?): List<FacetAttraction> {
        if (city != null) {
            val fromPopularity = jdbcTemplate.query(
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
            if (fromPopularity.isNotEmpty()) {
                return fromPopularity
            }
        }

        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        val fallback = jdbcTemplate.query(
            """
            SELECT id, name, popularity_score
            FROM poi
            WHERE active = 1
              $whereCity
            ORDER BY popularity_score DESC, id ASC
            LIMIT 16
            """.trimIndent(),
            { rs, _ ->
                FacetAttraction(
                    poi_id = rs.getLong("id"),
                    name = rs.getString("name"),
                    count = rs.getInt("popularity_score"),
                )
            },
            *params,
        )
        return fallback
    }

    private fun brandFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT b.id, b.name, COALESCE(cnt.cnt, 0) AS cnt
            FROM brand b
            LEFT JOIN (
              SELECT pb.brand_id, COUNT(*) AS cnt
              FROM property_brand pb
              JOIN property p ON p.id = pb.property_id
              WHERE p.status = 'ACTIVE'
                $whereCity
              GROUP BY pb.brand_id
            ) cnt ON cnt.brand_id = b.id
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
        val grouped = amenityFacets(city)
            .groupBy { it.group ?: "other" }

        val orderedGroups = listOf("service_option", "property_facility", "room_facility")
        val ordered = orderedGroups.map { group ->
            AmenityGroupFacet(
                group = group,
                items = (grouped[group] ?: emptyList()).sortedByDescending { it.count },
            )
        }

        val otherGroups = grouped.keys
            .filter { it !in orderedGroups }
            .sorted()
            .map { group ->
                AmenityGroupFacet(
                    group = group,
                    items = grouped[group]?.sortedByDescending { it.count } ?: emptyList(),
                )
            }

        return (ordered + otherGroups).filter { it.items.isNotEmpty() }
    }

    private fun starFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        val counts = jdbcTemplate.query(
            """
            SELECT star_rating, COUNT(*) AS cnt
            FROM property
            WHERE status = 'ACTIVE'
            $whereCity
            GROUP BY star_rating
            """.trimIndent(),
            { rs, _ -> rs.getInt("star_rating") to rs.getInt("cnt") },
            *params,
        ).toMap()
        return (5 downTo 1).map { star ->
            FacetCountItem(
                key = star.toString(),
                label = "${star}성급",
                count = counts[star] ?: 0,
            )
        }
    }

    private fun propertyTypeFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT pt.code, pt.label_ko, COALESCE(cnt.cnt, 0) AS cnt
            FROM property_type pt
            LEFT JOIN (
              SELECT p.property_type_code AS code, COUNT(*) AS cnt
              FROM property p
              WHERE p.status = 'ACTIVE'
                $whereCity
              GROUP BY p.property_type_code
            ) cnt ON cnt.code = pt.code
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
            SELECT po.code, po.label_ko, COALESCE(cnt.cnt, 0) AS cnt
            FROM payment_option po
            LEFT JOIN (
              SELECT ppo.payment_option_code AS code, COUNT(*) AS cnt
              FROM property_payment_option ppo
              JOIN property p ON p.id = ppo.property_id
              WHERE p.status = 'ACTIVE'
                $whereCity
              GROUP BY ppo.payment_option_code
            ) cnt ON cnt.code = po.code
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
            SELECT t.code, t.label_ko, COALESCE(cnt.cnt, 0) AS cnt
            FROM theme t
            LEFT JOIN (
              SELECT pt.theme_code AS code, COUNT(*) AS cnt
              FROM property_theme pt
              JOIN property p ON p.id = pt.property_id
              WHERE p.status = 'ACTIVE'
                $whereCity
              GROUP BY pt.theme_code
            ) cnt ON cnt.code = t.code
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
            SELECT a.code, a.label_ko, a.group_code, COALESCE(cnt.cnt, 0) AS cnt
            FROM amenity a
            LEFT JOIN (
              SELECT pa.amenity_code AS code, COUNT(*) AS cnt
              FROM property_amenity pa
              JOIN property p ON p.id = pa.property_id
              WHERE p.status = 'ACTIVE'
                $whereCity
              GROUP BY pa.amenity_code
            ) cnt ON cnt.code = a.code
            ORDER BY cnt DESC, a.code ASC
            """.trimIndent(),
            { rs, _ ->
                val code = rs.getString("code")
                FacetCountItem(
                    key = code,
                    label = rs.getString("label_ko"),
                    count = rs.getInt("cnt"),
                    group = normalizeAmenityGroup(rs.getString("group_code"), code),
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
        return row ?: defaultRatingBands()
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
        return row ?: defaultRatingBands()
    }

    private fun distanceBands(city: String?): List<FacetCountItem> {
        val labels = listOf(
            "center" to "도심에 위치",
            "under_2km" to "도심까지 2km 미만",
            "2_5km" to "도심까지 2~5km",
            "5_10km" to "도심까지 5~10km",
            "under_10km" to "도심까지 10km 미만",
        )
        val center = cityCenter(city) ?: return labels.map { (key, label) -> FacetCountItem(key, label, 0) }
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
                AND p.lat IS NOT NULL
                AND p.lng IS NOT NULL
                ${if (city != null) "AND p.city = ?" else ""}
            ) scoped
            """.trimIndent(),
            { rs, _ ->
                mapOf(
                    "center" to rs.getInt("center_cnt"),
                    "under_2km" to rs.getInt("under_2km_cnt"),
                    "2_5km" to rs.getInt("between_2_5_cnt"),
                    "5_10km" to rs.getInt("between_5_10_cnt"),
                    "under_10km" to rs.getInt("under_10km_cnt"),
                )
            },
            *(if (city != null) arrayOf(city) else emptyArray()),
        ).firstOrNull() ?: emptyMap()

        return labels.map { (key, label) ->
            FacetCountItem(key = key, label = label, count = row[key] ?: 0)
        }
    }

    private fun bedTypeFacets(city: String?): List<FacetCountItem> {
        val whereCity = if (city != null) "AND p.city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        val counts = jdbcTemplate.query(
            """
            SELECT UPPER(rt.bed_type) AS bed_type, COUNT(DISTINCT p.id) AS cnt
            FROM room_type rt
            JOIN property p ON p.id = rt.property_id
            WHERE p.status = 'ACTIVE'
              AND rt.status = 'ACTIVE'
              AND rt.bed_type IS NOT NULL
              AND rt.bed_type <> ''
              $whereCity
            GROUP BY UPPER(rt.bed_type)
            """.trimIndent(),
            { rs, _ -> rs.getString("bed_type") to rs.getInt("cnt") },
            *params,
        ).toMap()

        val defaults = listOf("DOUBLE", "TWIN", "QUEEN", "KING", "BUNK")
        val known = defaults.map { code ->
            FacetCountItem(
                key = code,
                label = bedTypeLabel(code),
                count = counts[code] ?: 0,
            )
        }
        val extras = counts.entries
            .filter { it.key !in defaults }
            .sortedBy { it.key }
            .map { (code, count) ->
                FacetCountItem(code, bedTypeLabel(code), count)
            }
        return known + extras
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
                mapOf(
                    "1" to rs.getInt("b1"),
                    "2" to rs.getInt("b2"),
                    "3" to rs.getInt("b3"),
                )
            },
            *params,
        ).firstOrNull() ?: emptyMap()

        return listOf(
            FacetCountItem("1", "스튜디오 / 침실 1개", row["1"] ?: 0),
            FacetCountItem("2", "침실 2개", row["2"] ?: 0),
            FacetCountItem("3", "침실 3+개", row["3"] ?: 0),
        )
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
        return listOf(FacetCountItem("beach_nearby", "전용 해변", count))
    }

    private fun cityCenter(city: String?): FacetLatLng? {
        val whereCity = if (city != null) "AND city = ?" else ""
        val params = if (city != null) arrayOf<Any>(city) else emptyArray()
        return jdbcTemplate.query(
            """
            SELECT AVG(lat) AS lat, AVG(lng) AS lng
            FROM property
            WHERE status = 'ACTIVE'
              AND lat IS NOT NULL
              AND lng IS NOT NULL
              $whereCity
            """.trimIndent(),
            { rs, _ ->
                val lat = rs.getDouble("lat")
                val lng = rs.getDouble("lng")
                if (rs.wasNull()) null else FacetLatLng(lat, lng)
            },
            *params,
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

    private fun defaultRatingBands(): List<FacetCountItem> {
        return listOf(
            FacetCountItem("9_plus", "9+ 최고", 0),
            FacetCountItem("8_plus", "8+ 우수", 0),
            FacetCountItem("7_plus", "7+ 좋음", 0),
            FacetCountItem("6_plus", "6+ 양호", 0),
        )
    }

    private fun defaultDistrictBlurb(city: String?, district: String): String {
        if ((city ?: "").equals("Seoul", ignoreCase = true)) {
            val seoulBlurb = mapOf(
                "Gangnam" to "비즈니스와 쇼핑 접근성이 좋은 핵심 지역",
                "Myeongdong" to "쇼핑과 미식, 교통 접근성이 뛰어난 관광 중심지",
                "Hongdae" to "트렌디한 카페/야간문화가 활발한 젊은 상권",
                "Jongno" to "고궁과 전통거리 중심의 문화 관광 지역",
                "Songpa" to "대형 복합몰과 가족형 레저 수요가 높은 지역",
            )
            return seoulBlurb[district] ?: "숙소/관광 접근성이 우수한 지역"
        }
        return "숙소/관광 접근성이 우수한 지역"
    }

    private fun normalizeAmenityGroup(rawGroup: String?, code: String): String {
        val normalized = rawGroup?.trim()?.lowercase()
        return when (normalized) {
            "service_option", "dining" -> "service_option"
            "property_facility", "essential", "wellness", "popular_condition" -> "property_facility"
            "room_facility", "room", "view" -> "room_facility"
            else -> when (code) {
                in serviceOptionCodes -> "service_option"
                in roomFacilityCodes -> "room_facility"
                else -> "property_facility"
            }
        }
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

    companion object {
        private val serviceOptionCodes = setOf(
            "breakfast",
            "food_delivery_external",
            "family_delivery_allowed",
            "early_checkin",
            "espresso_machine",
            "late_checkout",
            "convenience_delivery",
            "free_snack",
            "airport_transfer",
            "treadmill",
            "dinner_included",
            "afternoon_tea",
        )
        private val roomFacilityCodes = setOf(
            "fridge",
            "air_conditioning",
            "tv",
            "heating",
            "washer",
            "coffee_maker",
            "bathtub",
            "toiletries",
            "kitchen",
            "balcony",
            "private_pool",
            "ocean_view",
        )
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
