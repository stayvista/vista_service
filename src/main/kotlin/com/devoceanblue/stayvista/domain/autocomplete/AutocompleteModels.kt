package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType

data class AutocompleteQuery(
    val q: String?,
    val types: Set<PlaceType>,
    val size: Int,
    val lang: String,
    val principalKey: String,
)

data class AutocompleteData(
    val q: String,
    val items: List<AutocompleteItem>,
    val meta: AutocompleteMeta,
)

data class AutocompleteMeta(
    val types: List<String>,
    val size: Int,
    val lang: String,
    val took_ms: Long,
    val cache_hit: Boolean,
)

data class AutocompleteItem(
    val type: String,
    val id: String,
    val display: String,
    val subtitle: String? = null,
    val highlight: String? = null,
    val geo: AutocompleteGeo? = null,
    val score: Double = 0.0,
    val source: String,
    val bucket: String? = null,
)

data class AutocompleteGeo(
    val lat: Double,
    val lng: Double,
)

data class AutocompleteCandidate(
    val type: PlaceType,
    val canonicalId: String,
    val display: String,
    val subtitle: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val score: Double = 0.0,
    val source: String,
    val bucket: String? = null,
) {
    fun dedupeKey(): String = "${type.name}:${canonicalId.lowercase()}"

    fun toItem(highlight: String?): AutocompleteItem {
        return AutocompleteItem(
            type = type.name,
            id = PlaceIdCodec.encode(type, canonicalId),
            display = display,
            subtitle = subtitle,
            highlight = highlight,
            geo = if (lat != null && lng != null) AutocompleteGeo(lat, lng) else null,
            score = score,
            source = source,
            bucket = bucket,
        )
    }
}

data class AutocompleteImpressionRequest(
    val session_id: String? = null,
    val anon_id: String? = null,
    val q: String? = null,
    val lang: String? = null,
    val types: List<String> = emptyList(),
    val size: Int? = null,
    val items: List<AutocompleteFeedbackItem> = emptyList(),
)

data class AutocompleteSelectRequest(
    val session_id: String? = null,
    val anon_id: String? = null,
    val q: String? = null,
    val lang: String? = null,
    val types: List<String> = emptyList(),
    val size: Int? = null,
    val selected: AutocompleteFeedbackItem,
    val items: List<AutocompleteFeedbackItem> = emptyList(),
)

data class AutocompleteFeedbackItem(
    val id: String,
    val type: String? = null,
    val display: String? = null,
    val subtitle: String? = null,
    val geo: AutocompleteGeo? = null,
    val position: Int? = null,
    val score: Double? = null,
)

data class AutocompleteFeedbackResult(
    val accepted: Boolean,
)

data class AutocompleteMetricRow(
    val type: PlaceType,
    val canonicalId: String,
    val impressions7d: Long,
    val selects7d: Long,
) {
    val ctr7d: Double = if (impressions7d > 0) selects7d.toDouble() / impressions7d.toDouble() else 0.0
    val popularity7d: Long = impressions7d
}

fun parseRequestedTypes(rawTypes: String?): LinkedHashSet<PlaceType> {
    if (rawTypes.isNullOrBlank()) {
        return linkedSetOf(
            PlaceType.CITY,
            PlaceType.PROPERTY,
            PlaceType.POI,
            PlaceType.STATION,
            PlaceType.AIRPORT,
        )
    }

    val resolved = linkedSetOf<PlaceType>()
    rawTypes.split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { token ->
            val type = runCatching { PlaceType.valueOf(token.uppercase()) }
                .getOrElse {
                    throw DomainException(
                        errorCode = ErrorCode.VALIDATION_ERROR,
                        message = "Unsupported autocomplete type",
                        details = mapOf("type" to token),
                    )
                }
            resolved.add(type)
        }

    if (resolved.isEmpty()) {
        throw DomainException(
            errorCode = ErrorCode.VALIDATION_ERROR,
            message = "At least one autocomplete type is required",
        )
    }

    return resolved
}

fun parseFeedbackTypes(rawTypes: List<String>): List<String> {
    return rawTypes
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { token ->
            runCatching { PlaceType.valueOf(token.uppercase()) }
                .getOrElse {
                    throw DomainException(
                        errorCode = ErrorCode.VALIDATION_ERROR,
                        message = "Unsupported autocomplete feedback type",
                        details = mapOf("type" to token),
                    )
                }
                .name
        }
}
