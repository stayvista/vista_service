package com.devoceanblue.stayvista.domain.geo

import com.devoceanblue.stayvista.common.cache.SimpleTtlCache
import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Service

@Service
class GeoService(
    private val mapper: GeoMapper,
    private val cache: SimpleTtlCache,
) {
    fun nearbyPois(request: NearbyPoiRequest): NearbyPoiData {
        if (request.radius_m > 10_000) {
            throw DomainException(ErrorCode.VALIDATION_ERROR, "radius_m must be <= 10000")
        }
        val cacheKey = "geo:${request.lat}:${request.lng}:${request.radius_m}:${request.category}:${request.limit}"
        cache.get<NearbyPoiData>(cacheKey)?.let { return it }

        val pois = if (request.category.isNullOrBlank()) {
            mapper.listPois(limit = 1000)
        } else {
            mapper.listPoisByCategory(category = request.category, limit = 1000)
        }

        val filtered = pois
            .map { poi ->
                val distance = haversineMeters(request.lat, request.lng, poi.lat, poi.lng)
                NearbyPoiItem(
                    poi_id = "poi_${poi.id}",
                    name = poi.name,
                    category = poi.category,
                    distance_m = distance.toInt(),
                    location = PoiLocation(
                        lat = poi.lat,
                        lng = poi.lng,
                    ),
                )
            }
            .filter { it.distance_m <= request.radius_m }
            .sortedBy { it.distance_m }
            .take(request.limit.coerceIn(1, 50))

        val data = NearbyPoiData(items = filtered)
        cache.put(cacheKey, ttlMillis = 30_000, value = data)
        return data
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}

data class NearbyPoiRequest(
    val lat: Double,
    val lng: Double,
    val radius_m: Int,
    val category: String?,
    val limit: Int,
)

data class NearbyPoiData(
    val items: List<NearbyPoiItem>,
)

data class NearbyPoiItem(
    val poi_id: String,
    val name: String,
    val category: String?,
    val distance_m: Int,
    val location: PoiLocation,
)

data class PoiLocation(
    val lat: Double,
    val lng: Double,
)

data class PoiRow(
    val id: Long,
    val name: String,
    val category: String?,
    val lat: Double,
    val lng: Double,
)

@Mapper
interface GeoMapper {
    @Select(
        """
        SELECT id, name, category, lat, lng
        FROM poi
        LIMIT #{limit}
        """,
    )
    fun listPois(@Param("limit") limit: Int): List<PoiRow>

    @Select(
        """
        SELECT id, name, category, lat, lng
        FROM poi
        WHERE category = #{category}
        LIMIT #{limit}
        """,
    )
    fun listPoisByCategory(
        @Param("category") category: String,
        @Param("limit") limit: Int,
    ): List<PoiRow>
}
