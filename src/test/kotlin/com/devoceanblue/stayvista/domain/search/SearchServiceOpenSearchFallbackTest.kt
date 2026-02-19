package com.devoceanblue.stayvista.domain.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
    properties = [
        "stayvista.search.use-opensearch=true",
    ],
)
class SearchServiceOpenSearchFallbackTest {
    @Autowired
    lateinit var searchService: SearchService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @MockitoBean
    lateinit var openSearchClient: OpenSearchClient

    @BeforeEach
    fun setupSchemaAndData() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS property (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              city VARCHAR(100),
              status VARCHAR(20) NOT NULL,
              rating DECIMAL(3,2),
              thumbnail_url VARCHAR(255)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS room_type (
              id BIGINT PRIMARY KEY,
              property_id BIGINT NOT NULL,
              status VARCHAR(20) NOT NULL,
              base_price BIGINT NOT NULL
            )
            """.trimIndent(),
        )

        jdbcTemplate.update("DELETE FROM room_type")
        jdbcTemplate.update("DELETE FROM property")

        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, status, rating, thumbnail_url)
            VALUES (1101, 'Global Skyline Dubai Hotel', 'Dubai', 'ACTIVE', 4.6, 'https://img/dubai')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO room_type(id, property_id, status, base_price)
            VALUES (2101, 1101, 'ACTIVE', 230000)
            """.trimIndent(),
        )
    }

    @Test
    fun `should fallback to db when opensearch returns empty result`() {
        val request = SearchRequest(
            q = null,
            city = "Dubai",
            check_in = null,
            check_out = null,
            adults = null,
            children = null,
            min_price = null,
            max_price = null,
            min_rating = null,
            sort = null,
            cursor = null,
            limit = 20,
        )
        given(openSearchClient.search(request))
            .willReturn(SearchData(items = emptyList(), next_cursor = null))

        val result = searchService.search(request)

        assertEquals(1, result.items.size)
        assertEquals(1101L, result.items.first().property_id)
        assertEquals("Dubai", result.items.first().city)
        then(openSearchClient).should().search(request)
    }
}
