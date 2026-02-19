package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import com.devoceanblue.stayvista.common.web.RequestIdContext
import com.devoceanblue.stayvista.domain.common.DomainSupportService
import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType
import java.time.Instant
import org.springframework.stereotype.Service

@Service
class AutocompleteFeedbackService(
    private val domainSupportService: DomainSupportService,
    private val cacheService: AutocompleteCacheService,
) {
    fun recordImpression(
        request: AutocompleteImpressionRequest,
        principalKey: String,
    ): AutocompleteFeedbackResult {
        val normalizedItems = normalizeItems(request.items)
        if (normalizedItems.isEmpty()) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "impression items are required",
            )
        }

        domainSupportService.appendOutbox(
            aggregateType = "AUTOCOMPLETE",
            aggregateId = principalKey,
            eventType = "ac_impression",
            payload = mapOf(
                "request_id" to RequestIdContext.current(),
                "session_id" to request.session_id,
                "principal_key" to principalKey,
                "anon_id" to request.anon_id,
                "q" to request.q,
                "lang" to request.lang,
                "types" to parseFeedbackTypes(request.types),
                "size" to request.size,
                "items" to normalizedItems,
                "ts" to Instant.now().toString(),
            ),
        )
        return AutocompleteFeedbackResult(accepted = true)
    }

    fun recordSelect(
        request: AutocompleteSelectRequest,
        principalKey: String,
    ): AutocompleteFeedbackResult {
        val normalizedItems = normalizeItems(request.items)
        val selected = normalizeItem(request.selected)
        domainSupportService.appendOutbox(
            aggregateType = "AUTOCOMPLETE",
            aggregateId = principalKey,
            eventType = "ac_select",
            payload = mapOf(
                "request_id" to RequestIdContext.current(),
                "session_id" to request.session_id,
                "principal_key" to principalKey,
                "anon_id" to request.anon_id,
                "q" to request.q,
                "lang" to request.lang,
                "types" to parseFeedbackTypes(request.types),
                "size" to request.size,
                "items" to normalizedItems,
                "selected" to selected,
                "ts" to Instant.now().toString(),
            ),
        )

        cacheService.pushRecent(
            principalKey = principalKey,
            candidate = AutocompleteCandidate(
                type = selected.type,
                canonicalId = selected.canonical_id,
                display = selected.display ?: selected.canonical_id,
                subtitle = selected.subtitle,
                lat = selected.geo?.lat,
                lng = selected.geo?.lng,
                score = selected.score ?: 0.0,
                source = "recent",
                bucket = "recent",
            ),
        )

        return AutocompleteFeedbackResult(accepted = true)
    }

    private fun normalizeItems(items: List<AutocompleteFeedbackItem>): List<FeedbackItemPayload> {
        return items.map { normalizeItem(it) }
    }

    private fun normalizeItem(item: AutocompleteFeedbackItem): FeedbackItemPayload {
        val parsedPlaceId = PlaceIdCodec.parseOrNull(item.id)
            ?: throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "feedback item id is required",
            )

        val providedType = item.type?.trim()?.uppercase()
        if (!providedType.isNullOrBlank() && providedType != parsedPlaceId.type.name) {
            throw DomainException(
                errorCode = ErrorCode.VALIDATION_ERROR,
                message = "feedback item type mismatched with id",
                details = mapOf("id" to item.id, "type" to item.type),
            )
        }

        return FeedbackItemPayload(
            type = parsedPlaceId.type,
            canonical_id = parsedPlaceId.canonicalId,
            display = item.display?.trim()?.takeIf { it.isNotBlank() },
            subtitle = item.subtitle?.trim()?.takeIf { it.isNotBlank() },
            geo = item.geo,
            position = item.position?.coerceAtLeast(0),
            score = item.score,
        )
    }

    data class FeedbackItemPayload(
        val type: PlaceType,
        val canonical_id: String,
        val display: String? = null,
        val subtitle: String? = null,
        val geo: AutocompleteGeo? = null,
        val position: Int? = null,
        val score: Double? = null,
    )
}
