package com.devoceanblue.stayvista.domain.common

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode

enum class PlaceType {
    CITY,
    PROPERTY,
    POI,
    STATION,
    AIRPORT,
}

data class PlaceId(
    val type: PlaceType,
    val canonicalId: String,
)

object PlaceIdCodec {
    fun encode(type: PlaceType, canonicalId: String): String {
        return "${type.name.lowercase()}:${canonicalId.trim()}"
    }

    fun parseOrNull(raw: String?): PlaceId? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val splitIndex = raw.indexOf(':')
        if (splitIndex <= 0 || splitIndex == raw.length - 1) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "place_id must be formatted as type:canonical_id",
                details = mapOf("place_id" to raw),
            )
        }

        val rawType = raw.substring(0, splitIndex).trim().uppercase()
        val canonical = raw.substring(splitIndex + 1).trim()
        if (canonical.isBlank()) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "place_id canonical id is required",
                details = mapOf("place_id" to raw),
            )
        }

        val type = runCatching { PlaceType.valueOf(rawType) }
            .getOrElse {
                throw DomainException(
                    errorCode = ErrorCode.VALIDATION_ERROR,
                    message = "Unsupported place type",
                    details = mapOf("place_type" to rawType),
                )
            }

        return PlaceId(type = type, canonicalId = canonical)
    }
}
