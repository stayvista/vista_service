package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Service

@Service
class SearchFacetService(
    private val mapper: SearchFacetMapper,
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
                mapper.findPoiCity(poiId)?.let(CityCanonicalizer::canonicalize)
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
        val fromDistrict = mapper.listDistrictFacets(city)
            .map { row ->
                FacetDistrict(
                    id = row.id,
                    name = row.name,
                    blurb = row.blurb,
                    count = row.count,
                )
            }
        if (fromDistrict.isNotEmpty()) {
            return fromDistrict
        }

        return mapper.listDistrictFallbackFacets(city).mapIndexed { index, row ->
            FacetDistrict(
                id = (index + 1).toLong(),
                name = row.districtName,
                blurb = defaultDistrictBlurb(city, row.districtName),
                count = row.count,
            )
        }
    }

    private fun nearbyAttractions(city: String?): List<FacetAttraction> {
        if (city != null) {
            val fromPopularity = mapper.listCityPopularPois(city)
                .map { row -> FacetAttraction(row.poiId, row.name, row.count) }
            if (fromPopularity.isNotEmpty()) {
                return fromPopularity
            }
        }

        return mapper.listNearbyAttractionFallback(city)
            .map { row -> FacetAttraction(row.poiId, row.name, row.count) }
    }

    private fun brandFacets(city: String?): List<FacetCountItem> {
        return mapper.listBrandFacets(city)
            .map { row ->
                FacetCountItem(
                    key = row.key,
                    label = row.label,
                    count = row.count,
                )
            }
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
        val counts = mapper.listStarCounts(city).associate { it.starRating to it.count }
        return (5 downTo 1).map { star ->
            FacetCountItem(
                key = star.toString(),
                label = "${star}성급",
                count = counts[star] ?: 0,
            )
        }
    }

    private fun propertyTypeFacets(city: String?): List<FacetCountItem> {
        return mapper.listPropertyTypeFacets(city)
            .map { row -> FacetCountItem(row.key, row.label, row.count) }
    }

    private fun paymentOptionFacets(city: String?): List<FacetCountItem> {
        return mapper.listPaymentOptionFacets(city)
            .map { row -> FacetCountItem(row.key, row.label, row.count) }
    }

    private fun themeFacets(city: String?): List<FacetCountItem> {
        return mapper.listThemeFacets(city)
            .map { row -> FacetCountItem(row.key, row.label, row.count) }
    }

    private fun amenityFacets(city: String?): List<FacetCountItem> {
        return mapper.listAmenityFacets(city)
            .map { row ->
                FacetCountItem(
                    key = row.key,
                    label = row.label,
                    count = row.count,
                    group = normalizeAmenityGroup(row.groupCode, row.key),
                )
            }
    }

    private fun guestRatingBands(city: String?): List<FacetCountItem> {
        val row = mapper.loadGuestRatingBandCounts(city) ?: return defaultRatingBands()
        return listOf(
            FacetCountItem("9_plus", "9+ 최고", row.g9),
            FacetCountItem("8_plus", "8+ 우수", row.g8),
            FacetCountItem("7_plus", "7+ 좋음", row.g7),
            FacetCountItem("6_plus", "6+ 양호", row.g6),
        )
    }

    private fun locationRatingBands(city: String?): List<FacetCountItem> {
        val row = mapper.loadLocationRatingBandCounts(city) ?: return defaultRatingBands()
        return listOf(
            FacetCountItem("9_plus", "9+ 최고", row.g9),
            FacetCountItem("8_plus", "8+ 우수", row.g8),
            FacetCountItem("7_plus", "7+ 좋음", row.g7),
            FacetCountItem("6_plus", "6+ 양호", row.g6),
        )
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
        val row = mapper.loadDistanceBandCounts(
            city = city,
            centerLat = center.lat,
            centerLng = center.lng,
        )

        return labels.map { (key, label) ->
            val count = when (key) {
                "center" -> row?.centerCnt ?: 0
                "under_2km" -> row?.under2kmCnt ?: 0
                "2_5km" -> row?.between2To5Cnt ?: 0
                "5_10km" -> row?.between5To10Cnt ?: 0
                "under_10km" -> row?.under10kmCnt ?: 0
                else -> 0
            }
            FacetCountItem(key = key, label = label, count = count)
        }
    }

    private fun bedTypeFacets(city: String?): List<FacetCountItem> {
        val counts = mapper.listBedTypeCounts(city).associate { it.bedType to it.count }

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
        val row = mapper.loadBedroomCounts(city)

        return listOf(
            FacetCountItem("1", "스튜디오 / 침실 1개", row?.b1 ?: 0),
            FacetCountItem("2", "침실 2개", row?.b2 ?: 0),
            FacetCountItem("3", "침실 3+개", row?.b3 ?: 0),
        )
    }

    private fun familyOptions(city: String?): List<FacetCountItem> {
        val count = mapper.countKidFreeStay(city)
        return listOf(FacetCountItem("kid_free_stay", "아동 무료 투숙 가능", count))
    }

    private fun beachOptions(city: String?): List<FacetCountItem> {
        val count = mapper.countBeachNearby(city)
        return listOf(FacetCountItem("beach_nearby", "전용 해변", count))
    }

    private fun cityCenter(city: String?): FacetLatLng? {
        return mapper.findCityCenter(city)?.let { row ->
            val lat = row.lat ?: return@let null
            val lng = row.lng ?: return@let null
            FacetLatLng(lat, lng)
        }
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

data class FacetLatLng(
    val lat: Double,
    val lng: Double,
)

data class FacetLatLngRow(
    val lat: Double?,
    val lng: Double?,
)

data class DistrictFacetRow(
    val id: Long,
    val name: String,
    val blurb: String?,
    val count: Int,
)

data class DistrictFallbackFacetRow(
    val districtName: String,
    val count: Int,
)

data class FacetAttractionRow(
    val poiId: Long,
    val name: String,
    val count: Int,
)

data class FacetCountRow(
    val key: String,
    val label: String,
    val count: Int,
)

data class AmenityFacetRow(
    val key: String,
    val label: String,
    val groupCode: String?,
    val count: Int,
)

data class StarCountRow(
    val starRating: Int,
    val count: Int,
)

data class RatingBandCountsRow(
    val g9: Int,
    val g8: Int,
    val g7: Int,
    val g6: Int,
)

data class DistanceBandCountsRow(
    val centerCnt: Int,
    val under2kmCnt: Int,
    val between2To5Cnt: Int,
    val between5To10Cnt: Int,
    val under10kmCnt: Int,
)

data class BedTypeCountRow(
    val bedType: String,
    val count: Int,
)

data class BedroomCountsRow(
    val b1: Int,
    val b2: Int,
    val b3: Int,
)

@Mapper
interface SearchFacetMapper {
    @Select("SELECT city FROM poi WHERE id = #{poiId} LIMIT 1")
    fun findPoiCity(@Param("poiId") poiId: Long): String?

    @Select(
        """
        <script>
        SELECT d.id,
               d.name,
               d.blurb,
               COALESCE(p.cnt, 0) AS count
        FROM district d
        LEFT JOIN (
          SELECT district_name, city, COUNT(*) AS cnt
          FROM property
          WHERE status = 'ACTIVE'
            AND district_name IS NOT NULL
            AND district_name &lt;&gt; ''
          GROUP BY district_name, city
        ) p ON p.district_name = d.name AND p.city = d.city
        <if test="city != null">
          WHERE d.city = #{city}
        </if>
        ORDER BY d.rank_score DESC, count DESC, d.id ASC
        LIMIT 16
        </script>
        """,
    )
    fun listDistrictFacets(@Param("city") city: String?): List<DistrictFacetRow>

    @Select(
        """
        <script>
        SELECT district_name AS districtName, COUNT(*) AS count
        FROM property
        WHERE status = 'ACTIVE'
          AND district_name IS NOT NULL
          AND district_name &lt;&gt; ''
          <if test="city != null">
            AND city = #{city}
          </if>
        GROUP BY district_name
        ORDER BY count DESC, district_name ASC
        LIMIT 16
        </script>
        """,
    )
    fun listDistrictFallbackFacets(@Param("city") city: String?): List<DistrictFallbackFacetRow>

    @Select(
        """
        SELECT p.id AS poiId, p.name, cpp.rank_score AS count
        FROM city_poi_popular cpp
        JOIN poi p ON p.id = cpp.poi_id
        WHERE cpp.city = #{city}
        ORDER BY cpp.rank_score DESC, p.id ASC
        LIMIT 16
        """,
    )
    fun listCityPopularPois(@Param("city") city: String): List<FacetAttractionRow>

    @Select(
        """
        <script>
        SELECT id AS poiId, name, popularity_score AS count
        FROM poi
        WHERE active = 1
          <if test="city != null">
            AND city = #{city}
          </if>
        ORDER BY popularity_score DESC, id ASC
        LIMIT 16
        </script>
        """,
    )
    fun listNearbyAttractionFallback(@Param("city") city: String?): List<FacetAttractionRow>

    @Select(
        """
        <script>
        SELECT CAST(b.id AS CHAR) AS key, b.name AS label, COALESCE(cnt.cnt, 0) AS count
        FROM brand b
        LEFT JOIN (
          SELECT pb.brand_id, COUNT(*) AS cnt
          FROM property_brand pb
          JOIN property p ON p.id = pb.property_id
          WHERE p.status = 'ACTIVE'
            <if test="city != null">
              AND p.city = #{city}
            </if>
          GROUP BY pb.brand_id
        ) cnt ON cnt.brand_id = b.id
        ORDER BY count DESC, b.name ASC
        LIMIT 24
        </script>
        """,
    )
    fun listBrandFacets(@Param("city") city: String?): List<FacetCountRow>

    @Select(
        """
        <script>
        SELECT star_rating AS starRating, COUNT(*) AS count
        FROM property
        WHERE status = 'ACTIVE'
          <if test="city != null">
            AND city = #{city}
          </if>
        GROUP BY star_rating
        </script>
        """,
    )
    fun listStarCounts(@Param("city") city: String?): List<StarCountRow>

    @Select(
        """
        <script>
        SELECT pt.code AS key, pt.label_ko AS label, COALESCE(cnt.cnt, 0) AS count
        FROM property_type pt
        LEFT JOIN (
          SELECT p.property_type_code AS code, COUNT(*) AS cnt
          FROM property p
          WHERE p.status = 'ACTIVE'
            <if test="city != null">
              AND p.city = #{city}
            </if>
          GROUP BY p.property_type_code
        ) cnt ON cnt.code = pt.code
        ORDER BY count DESC, pt.code ASC
        </script>
        """,
    )
    fun listPropertyTypeFacets(@Param("city") city: String?): List<FacetCountRow>

    @Select(
        """
        <script>
        SELECT po.code AS key, po.label_ko AS label, COALESCE(cnt.cnt, 0) AS count
        FROM payment_option po
        LEFT JOIN (
          SELECT ppo.payment_option_code AS code, COUNT(*) AS cnt
          FROM property_payment_option ppo
          JOIN property p ON p.id = ppo.property_id
          WHERE p.status = 'ACTIVE'
            <if test="city != null">
              AND p.city = #{city}
            </if>
          GROUP BY ppo.payment_option_code
        ) cnt ON cnt.code = po.code
        ORDER BY count DESC, po.code ASC
        </script>
        """,
    )
    fun listPaymentOptionFacets(@Param("city") city: String?): List<FacetCountRow>

    @Select(
        """
        <script>
        SELECT t.code AS key, t.label_ko AS label, COALESCE(cnt.cnt, 0) AS count
        FROM theme t
        LEFT JOIN (
          SELECT pt.theme_code AS code, COUNT(*) AS cnt
          FROM property_theme pt
          JOIN property p ON p.id = pt.property_id
          WHERE p.status = 'ACTIVE'
            <if test="city != null">
              AND p.city = #{city}
            </if>
          GROUP BY pt.theme_code
        ) cnt ON cnt.code = t.code
        ORDER BY count DESC, t.code ASC
        </script>
        """,
    )
    fun listThemeFacets(@Param("city") city: String?): List<FacetCountRow>

    @Select(
        """
        <script>
        SELECT a.code AS key,
               a.label_ko AS label,
               a.group_code AS groupCode,
               COALESCE(cnt.cnt, 0) AS count
        FROM amenity a
        LEFT JOIN (
          SELECT pa.amenity_code AS code, COUNT(*) AS cnt
          FROM property_amenity pa
          JOIN property p ON p.id = pa.property_id
          WHERE p.status = 'ACTIVE'
            <if test="city != null">
              AND p.city = #{city}
            </if>
          GROUP BY pa.amenity_code
        ) cnt ON cnt.code = a.code
        ORDER BY count DESC, a.code ASC
        </script>
        """,
    )
    fun listAmenityFacets(@Param("city") city: String?): List<AmenityFacetRow>

    @Select(
        """
        <script>
        SELECT
          COALESCE(SUM(CASE WHEN COALESCE(rating, 0) &gt;= 4.5 THEN 1 ELSE 0 END), 0) AS g9,
          COALESCE(SUM(CASE WHEN COALESCE(rating, 0) &gt;= 4.0 THEN 1 ELSE 0 END), 0) AS g8,
          COALESCE(SUM(CASE WHEN COALESCE(rating, 0) &gt;= 3.5 THEN 1 ELSE 0 END), 0) AS g7,
          COALESCE(SUM(CASE WHEN COALESCE(rating, 0) &gt;= 3.0 THEN 1 ELSE 0 END), 0) AS g6
        FROM property
        WHERE status = 'ACTIVE'
          <if test="city != null">
            AND city = #{city}
          </if>
        </script>
        """,
    )
    fun loadGuestRatingBandCounts(@Param("city") city: String?): RatingBandCountsRow?

    @Select(
        """
        <script>
        SELECT
          COALESCE(SUM(CASE WHEN COALESCE(location_rating, 0) &gt;= 4.5 THEN 1 ELSE 0 END), 0) AS g9,
          COALESCE(SUM(CASE WHEN COALESCE(location_rating, 0) &gt;= 4.0 THEN 1 ELSE 0 END), 0) AS g8,
          COALESCE(SUM(CASE WHEN COALESCE(location_rating, 0) &gt;= 3.5 THEN 1 ELSE 0 END), 0) AS g7,
          COALESCE(SUM(CASE WHEN COALESCE(location_rating, 0) &gt;= 3.0 THEN 1 ELSE 0 END), 0) AS g6
        FROM property
        WHERE status = 'ACTIVE'
          <if test="city != null">
            AND city = #{city}
          </if>
        </script>
        """,
    )
    fun loadLocationRatingBandCounts(@Param("city") city: String?): RatingBandCountsRow?

    @Select(
        """
        <script>
        SELECT
          COALESCE(SUM(CASE WHEN dist &lt;= 1000 THEN 1 ELSE 0 END), 0) AS centerCnt,
          COALESCE(SUM(CASE WHEN dist &lt;= 2000 THEN 1 ELSE 0 END), 0) AS under2kmCnt,
          COALESCE(SUM(CASE WHEN dist &gt; 2000 AND dist &lt;= 5000 THEN 1 ELSE 0 END), 0) AS between2To5Cnt,
          COALESCE(SUM(CASE WHEN dist &gt; 5000 AND dist &lt;= 10000 THEN 1 ELSE 0 END), 0) AS between5To10Cnt,
          COALESCE(SUM(CASE WHEN dist &lt;= 10000 THEN 1 ELSE 0 END), 0) AS under10kmCnt
        FROM (
          SELECT (
            6371000 * ACOS(
              COS(RADIANS(#{centerLat})) * COS(RADIANS(p.lat)) *
              COS(RADIANS(p.lng) - RADIANS(#{centerLng})) +
              SIN(RADIANS(#{centerLat})) * SIN(RADIANS(p.lat))
            )
          ) AS dist
          FROM property p
          WHERE p.status = 'ACTIVE'
            AND p.lat IS NOT NULL
            AND p.lng IS NOT NULL
            <if test="city != null">
              AND p.city = #{city}
            </if>
        ) scoped
        </script>
        """,
    )
    fun loadDistanceBandCounts(
        @Param("city") city: String?,
        @Param("centerLat") centerLat: Double,
        @Param("centerLng") centerLng: Double,
    ): DistanceBandCountsRow?

    @Select(
        """
        <script>
        SELECT UPPER(rt.bed_type) AS bedType, COUNT(DISTINCT p.id) AS count
        FROM room_type rt
        JOIN property p ON p.id = rt.property_id
        WHERE p.status = 'ACTIVE'
          AND rt.status = 'ACTIVE'
          AND rt.bed_type IS NOT NULL
          AND rt.bed_type &lt;&gt; ''
          <if test="city != null">
            AND p.city = #{city}
          </if>
        GROUP BY UPPER(rt.bed_type)
        </script>
        """,
    )
    fun listBedTypeCounts(@Param("city") city: String?): List<BedTypeCountRow>

    @Select(
        """
        <script>
        SELECT
          COUNT(DISTINCT CASE WHEN COALESCE(rt.bedrooms, 1) = 1 THEN p.id END) AS b1,
          COUNT(DISTINCT CASE WHEN COALESCE(rt.bedrooms, 1) = 2 THEN p.id END) AS b2,
          COUNT(DISTINCT CASE WHEN COALESCE(rt.bedrooms, 1) &gt;= 3 THEN p.id END) AS b3
        FROM property p
        JOIN room_type rt ON rt.property_id = p.id
        WHERE p.status = 'ACTIVE'
          AND rt.status = 'ACTIVE'
          <if test="city != null">
            AND p.city = #{city}
          </if>
        </script>
        """,
    )
    fun loadBedroomCounts(@Param("city") city: String?): BedroomCountsRow?

    @Select(
        """
        <script>
        SELECT COUNT(*) 
        FROM property
        WHERE status = 'ACTIVE'
          AND COALESCE(kid_free_stay, 0) = 1
          <if test="city != null">
            AND city = #{city}
          </if>
        </script>
        """,
    )
    fun countKidFreeStay(@Param("city") city: String?): Int

    @Select(
        """
        <script>
        SELECT COUNT(*)
        FROM property
        WHERE status = 'ACTIVE'
          AND (COALESCE(is_beachfront, 0) = 1 OR COALESCE(beach_distance_m, 999999) &lt;= 1000)
          <if test="city != null">
            AND city = #{city}
          </if>
        </script>
        """,
    )
    fun countBeachNearby(@Param("city") city: String?): Int

    @Select(
        """
        <script>
        SELECT AVG(lat) AS lat, AVG(lng) AS lng
        FROM property
        WHERE status = 'ACTIVE'
          AND lat IS NOT NULL
          AND lng IS NOT NULL
          <if test="city != null">
            AND city = #{city}
          </if>
        </script>
        """,
    )
    fun findCityCenter(@Param("city") city: String?): FacetLatLngRow?
}
