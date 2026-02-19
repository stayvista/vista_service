package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.domain.common.PlaceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
    properties = [
        "stayvista.autocomplete.aggregate.enabled=false",
    ],
)
class AutocompleteServiceTest {
    @Autowired
    lateinit var autocompleteService: AutocompleteService

    @MockitoBean
    lateinit var cacheService: AutocompleteCacheService

    @MockitoBean
    lateinit var openSearchGateway: AutocompleteOpenSearchGateway

    @MockitoBean
    lateinit var candidateRepository: AutocompleteCandidateRepository

    @Test
    fun `typed query should return cached result first`() {
        given(cacheService.queryKey("ko", setOf(PlaceType.CITY), "seoul", 10)).willReturn("ac:k")
        given(cacheService.getSuggestions("ac:k")).willReturn(
            listOf(
                AutocompleteCandidate(
                    type = PlaceType.CITY,
                    canonicalId = "Seoul",
                    display = "Seoul",
                    subtitle = "KR",
                    score = 11.0,
                    source = "redis",
                ),
            ),
        )

        val response = autocompleteService.autocomplete(
            AutocompleteQuery(
                q = "Seoul",
                types = setOf(PlaceType.CITY),
                size = 10,
                lang = "ko",
                principalKey = "anon:test",
            ),
        )

        assertTrue(response.meta.cache_hit)
        assertEquals(1, response.items.size)
        assertEquals("city:Seoul", response.items.first().id)
        then(openSearchGateway).shouldHaveNoInteractions()
    }

    @Test
    fun `typed query should fallback to db when opensearch throws`() {
        given(cacheService.queryKey("ko", setOf(PlaceType.PROPERTY), "river", 10)).willReturn("ac:db")
        given(cacheService.getSuggestions("ac:db")).willReturn(null)
        given(openSearchGateway.search("river", setOf(PlaceType.PROPERTY), 10, "ko"))
            .willThrow(IllegalStateException("os timeout"))
        given(candidateRepository.searchDatabaseCandidates("river", setOf(PlaceType.PROPERTY), 10)).willReturn(
            listOf(
                AutocompleteCandidate(
                    type = PlaceType.PROPERTY,
                    canonicalId = "1001",
                    display = "River View Hotel",
                    subtitle = "Seoul",
                    score = 4.7,
                    source = "db",
                ),
            ),
        )

        val response = autocompleteService.autocomplete(
            AutocompleteQuery(
                q = "river",
                types = setOf(PlaceType.PROPERTY),
                size = 10,
                lang = "ko",
                principalKey = "anon:test",
            ),
        )

        assertEquals(false, response.meta.cache_hit)
        assertEquals(1, response.items.size)
        assertEquals("PROPERTY", response.items.first().type)
        assertEquals("db", response.items.first().source)
        then(cacheService).should().putQuerySuggestions(anyString(), org.mockito.ArgumentMatchers.anyList())
    }

    @Test
    fun `empty query should merge recent and popular`() {
        given(cacheService.getRecent("anon:test")).willReturn(
            listOf(
                AutocompleteCandidate(
                    type = PlaceType.CITY,
                    canonicalId = "Seoul",
                    display = "Seoul",
                    subtitle = "recent",
                    score = 2.0,
                    source = "recent",
                    bucket = "recent",
                ),
            ),
        )
        given(cacheService.popularKey("ko", setOf(PlaceType.CITY, PlaceType.PROPERTY), 5)).willReturn("ac:popular")
        given(cacheService.getSuggestions("ac:popular")).willReturn(null)
        given(candidateRepository.loadPopularCandidates(setOf(PlaceType.CITY, PlaceType.PROPERTY), 5)).willReturn(
            listOf(
                AutocompleteCandidate(
                    type = PlaceType.PROPERTY,
                    canonicalId = "1002",
                    display = "Popular Stay",
                    subtitle = "Seoul",
                    score = 1.0,
                    source = "popular",
                    bucket = "popular",
                ),
            ),
        )

        val response = autocompleteService.autocomplete(
            AutocompleteQuery(
                q = "",
                types = setOf(PlaceType.CITY, PlaceType.PROPERTY),
                size = 5,
                lang = "ko",
                principalKey = "anon:test",
            ),
        )

        assertEquals(2, response.items.size)
        assertEquals("recent", response.items.first().source)
        then(candidateRepository).should().loadPopularCandidates(setOf(PlaceType.CITY, PlaceType.PROPERTY), 5)
        then(cacheService).should().putPopularSuggestions(anyString(), org.mockito.ArgumentMatchers.anyList())
        then(openSearchGateway).shouldHaveNoInteractions()
    }
}
