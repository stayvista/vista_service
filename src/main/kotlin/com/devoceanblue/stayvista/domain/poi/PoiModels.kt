package com.devoceanblue.stayvista.domain.poi

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private const val MIN_LAT = -90.0
private const val MAX_LAT = 90.0
private const val MIN_LNG = -180.0
private const val MAX_LNG = 180.0

data class PoiBoundingBox(
    val swLat: Double,
    val swLng: Double,
    val neLat: Double,
    val neLng: Double,
) {
    fun centerLat(): Double = (swLat + neLat) / 2.0

    fun centerLng(): Double = (swLng + neLng) / 2.0

    fun normalized(): String {
        return "%.6f,%.6f,%.6f,%.6f".format(swLat, swLng, neLat, neLng)
    }

    fun latSpan(): Double = neLat - swLat

    fun lngSpan(): Double = neLng - swLng

    fun areaHint(): Double {
        val latFactor = cos(Math.toRadians(abs(centerLat()).coerceAtMost(80.0)))
        return max(latSpan(), 0.000001) * max(lngSpan(), 0.000001) * latFactor
    }

    companion object {
        fun parse(raw: String): PoiBoundingBox {
            val parts = raw.split(",")
                .map { it.trim() }
            if (parts.size != 4) {
                throw DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "bbox must be formatted as swLat,swLng,neLat,neLng",
                )
            }
            val values = parts.map { it.toDoubleOrNull() }
            if (values.any { it == null }) {
                throw DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "bbox contains non numeric coordinates",
                )
            }

            val swLat = values[0]!!
            val swLng = values[1]!!
            val neLat = values[2]!!
            val neLng = values[3]!!

            if (swLat !in MIN_LAT..MAX_LAT || neLat !in MIN_LAT..MAX_LAT || swLng !in MIN_LNG..MAX_LNG || neLng !in MIN_LNG..MAX_LNG) {
                throw DomainException(ErrorCode.VALIDATION_ERROR, "bbox coordinates are out of range")
            }
            if (swLat >= neLat || swLng >= neLng) {
                throw DomainException(ErrorCode.VALIDATION_ERROR, "bbox requires sw < ne")
            }

            return PoiBoundingBox(
                swLat = min(swLat, neLat),
                swLng = min(swLng, neLng),
                neLat = max(swLat, neLat),
                neLng = max(swLng, neLng),
            )
        }
    }
}

data class PoiCenter(
    val lat: Double,
    val lng: Double,
) {
    companion object {
        fun parse(raw: String?): PoiCenter? {
            if (raw.isNullOrBlank()) {
                return null
            }
            val parts = raw.split(",")
                .map { it.trim() }
            if (parts.size != 2) {
                throw DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "center must be formatted as lat,lng",
                )
            }
            val lat = parts[0].toDoubleOrNull()
            val lng = parts[1].toDoubleOrNull()
            if (lat == null || lng == null) {
                throw DomainException(ErrorCode.VALIDATION_ERROR, "center contains non numeric coordinates")
            }
            if (lat !in MIN_LAT..MAX_LAT || lng !in MIN_LNG..MAX_LNG) {
                throw DomainException(ErrorCode.VALIDATION_ERROR, "center coordinates are out of range")
            }
            return PoiCenter(lat = lat, lng = lng)
        }
    }
}

enum class PoiSort(
    val apiValue: String,
) {
    DISTANCE("distance"),
    POPULARITY("popularity"),
    RATING("rating"),
    ;

    companion object {
        fun parse(raw: String?): PoiSort {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) {
                return DISTANCE
            }
            return entries.firstOrNull { it.apiValue == normalized }
                ?: throw DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "sort must be one of: distance, popularity, rating",
                )
        }
    }
}

data class PoiNearbyQuery(
    val bbox: PoiBoundingBox,
    val category: String?,
    val limit: Int,
    val offset: Int,
    val sort: PoiSort,
    val center: PoiCenter?,
    val radius_m: Int?,
)

data class PoiNearbyData(
    val items: List<PoiNearbyItem>,
    val meta: PoiNearbyMeta,
)

data class PoiNearbyItem(
    val id: Long,
    val name: String,
    val category: String?,
    val lat: Double,
    val lng: Double,
    val distance_m: Int,
    val preview: PoiNearbyPreview?,
)

data class PoiNearbyPreview(
    val thumbnail_url: String?,
    val address: String?,
    val snippet: String?,
)

data class PoiNearbyMeta(
    val bbox: String,
    val returned: Int,
    val has_more: Boolean,
    val offset: Int,
    val limit: Int,
)

data class PoiDetailData(
    val id: Long,
    val name: String,
    val category: String?,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val description: String?,
    val images: List<String>,
    val links: PoiExternalLinks,
    val related: PoiRelatedHints,
)

data class PoiExternalLinks(
    val naver: String,
    val google: String,
    val osm: String,
)

data class PoiRelatedHints(
    val properties: List<PoiRelatedProperty>,
    val products: List<PoiRelatedProduct>,
)

data class PoiRelatedProperty(
    val property_id: Long,
    val name: String,
    val city: String?,
    val rating: Double,
    val thumbnail_url: String?,
)

data class PoiRelatedProduct(
    val product_id: Long,
    val name: String,
    val category: String,
    val city: String?,
)

data class AdminPoiCreateRequest(
    @field:NotBlank
    val name: String,
    val category: String? = null,
    val city: String? = null,
    @field:DecimalMin("-90.0")
    @field:DecimalMax("90.0")
    val lat: Double,
    @field:DecimalMin("-180.0")
    @field:DecimalMax("180.0")
    val lng: Double,
    val address: String? = null,
    val description: String? = null,
    val images: List<String> = emptyList(),
    @field:Min(0)
    val popularity_score: Int = 0,
    @field:DecimalMin("0.0")
    @field:DecimalMax("5.0")
    val rating_score: Double = 0.0,
    val active: Boolean = true,
)

data class AdminPoiPatchRequest(
    val name: String? = null,
    val category: String? = null,
    val city: String? = null,
    @field:DecimalMin("-90.0")
    @field:DecimalMax("90.0")
    val lat: Double? = null,
    @field:DecimalMin("-180.0")
    @field:DecimalMax("180.0")
    val lng: Double? = null,
    val address: String? = null,
    val description: String? = null,
    val images: List<String>? = null,
    @field:Min(0)
    val popularity_score: Int? = null,
    @field:DecimalMin("0.0")
    @field:DecimalMax("5.0")
    val rating_score: Double? = null,
    val active: Boolean? = null,
)

data class AdminPoiSummary(
    val id: Long,
    val name: String,
    val category: String?,
    val city: String?,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val active: Boolean,
)

data class AdminPoiListData(
    val items: List<AdminPoiSummary>,
    val limit: Int,
    val offset: Int,
    val has_more: Boolean,
)

data class AdminPoiDetail(
    val id: Long,
    val name: String,
    val category: String?,
    val city: String?,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val description: String?,
    val images: List<String>,
    val popularity_score: Int,
    val rating_score: Double,
    val active: Boolean,
)

data class PoiGeohashBackfillData(
    val scanned: Int,
    val updated: Int,
)

data class PoiRateLimitDecision(
    val allowed: Boolean,
    val retryAfterMs: Long,
)

class NearbyRateLimitExceededException(
    val retryAfterMs: Long,
) : RuntimeException("Too many nearby requests")
