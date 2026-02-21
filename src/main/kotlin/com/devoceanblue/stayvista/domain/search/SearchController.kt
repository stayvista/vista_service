package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/v1/search")
class SearchController(
    private val searchService: SearchService,
    private val searchFacetService: SearchFacetService,
) {
    @GetMapping("/properties")
    fun searchProperties(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) city: String?,
        @RequestParam(required = false) place_id: String?,
        @RequestParam(required = false) check_in: String?,
        @RequestParam(required = false) check_out: String?,
        @RequestParam(required = false) adults: Int?,
        @RequestParam(required = false) children: Int?,
        @RequestParam(required = false) rooms: Int?,
        @RequestParam(required = false) children_ages: String?,
        @RequestParam(required = false) currency: String?,
        @RequestParam(required = false) min_price: Long?,
        @RequestParam(required = false) max_price: Long?,
        @RequestParam(required = false) min_rating: Double?,
        @RequestParam(required = false) min_guest_rating: Double?,
        @RequestParam(required = false) min_location_rating: Double?,
        @RequestParam(required = false) max_distance_m: Int?,
        @RequestParam(required = false) stars: String?,
        @RequestParam(required = false) amenities: String?,
        @RequestParam(required = false) property_type: String?,
        @RequestParam(required = false) districts: String?,
        @RequestParam(required = false) payment_options: String?,
        @RequestParam(required = false) themes: String?,
        @RequestParam(required = false) brands: String?,
        @RequestParam(required = false) bed_types: String?,
        @RequestParam(required = false) bedrooms: Int?,
        @RequestParam(required = false) nearby_attractions: String?,
        @RequestParam(required = false) guest_rating_bands: String?,
        @RequestParam(required = false) location_rating_bands: String?,
        @RequestParam(required = false) distance_bands: String?,
        @RequestParam(required = false) family_options: String?,
        @RequestParam(required = false) beach_options: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) @Min(1) page: Int?,
        @RequestParam(required = false) @Min(1) @Max(50) size: Int?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int,
    ) = ApiResponses.ok(
        searchService.search(
            SearchRequest(
                q = q,
                city = city,
                place_id = place_id,
                check_in = check_in,
                check_out = check_out,
                adults = adults,
                children = children,
                rooms = rooms,
                children_ages = parseIntList(children_ages),
                currency = currency ?: "KRW",
                min_price = min_price,
                max_price = max_price,
                min_rating = min_rating,
                min_guest_rating = min_guest_rating,
                min_location_rating = min_location_rating,
                max_distance_m = max_distance_m,
                stars = parseIntList(stars),
                amenities = parseStringList(amenities),
                property_type = parseStringList(property_type),
                districts = parseStringList(districts),
                payment_options = parseStringList(payment_options),
                themes = parseStringList(themes),
                brands = parseStringList(brands),
                bed_types = parseStringList(bed_types),
                bedrooms = bedrooms,
                nearby_attractions = parseLongList(nearby_attractions),
                guest_rating_bands = parseStringList(guest_rating_bands),
                location_rating_bands = parseStringList(location_rating_bands),
                distance_bands = parseStringList(distance_bands),
                family_options = parseStringList(family_options),
                beach_options = parseStringList(beach_options),
                sort = sort,
                page = page,
                size = size,
                cursor = cursor,
                limit = limit,
            ),
        ),
    )

    @GetMapping("/facets")
    fun searchFacets(
        @RequestParam(required = false) place_id: String?,
        @RequestParam(required = false) city: String?,
    ) = ApiResponses.ok(
        searchFacetService.facets(
            placeId = place_id,
            city = city,
        ),
    )

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseIntList(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
    }

    private fun parseLongList(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
    }
}
