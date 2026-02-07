package com.devoceanblue.stayvista.domain.search

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(
    properties = [
        "stayvista.search.use-opensearch=false",
    ],
)
class SearchServiceTest {
    @Autowired
    lateinit var searchService: SearchService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

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
            "INSERT INTO property(id, name, city, status, rating, thumbnail_url) VALUES (1001, 'Alpha Hotel', 'Seoul', 'ACTIVE', 4.8, 'https://img/alpha')",
        )
        jdbcTemplate.update(
            "INSERT INTO property(id, name, city, status, rating, thumbnail_url) VALUES (1002, 'Bravo Stay', 'Seoul', 'ACTIVE', 3.9, 'https://img/bravo')",
        )
        jdbcTemplate.update(
            "INSERT INTO property(id, name, city, status, rating, thumbnail_url) VALUES (1003, 'Charlie House', 'Busan', 'ACTIVE', 4.5, 'https://img/charlie')",
        )
        jdbcTemplate.update(
            "INSERT INTO property(id, name, city, status, rating, thumbnail_url) VALUES (1004, 'Dormant Inn', 'Seoul', 'INACTIVE', 4.9, 'https://img/inactive')",
        )

        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, base_price) VALUES (2001, 1001, 'ACTIVE', 180000)")
        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, base_price) VALUES (2002, 1002, 'ACTIVE', 90000)")
        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, base_price) VALUES (2003, 1003, 'ACTIVE', 150000)")
        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, base_price) VALUES (2004, 1001, 'INACTIVE', 50000)")
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.update("DELETE FROM room_type")
        jdbcTemplate.update("DELETE FROM property")
    }

    @Test
    fun `search should apply city price and rating filters`() {
        val result = searchService.search(
            SearchRequest(
                q = null,
                city = "Seoul",
                check_in = null,
                check_out = null,
                adults = null,
                children = null,
                min_price = 100000,
                max_price = 200000,
                min_rating = 4.5,
                sort = "price_asc",
                cursor = null,
                limit = 20,
            ),
        )

        assertEquals(1, result.items.size)
        assertEquals(1001L, result.items.first().property_id)
        assertEquals(180000L, result.items.first().price_min)
        assertTrue(result.items.first().rating >= 4.5)
    }

    @Test
    fun `search should sort by rating desc and skip inactive property`() {
        val result = searchService.search(
            SearchRequest(
                q = null,
                city = null,
                check_in = null,
                check_out = null,
                adults = null,
                children = null,
                min_price = null,
                max_price = null,
                min_rating = null,
                sort = "rating_desc",
                cursor = null,
                limit = 20,
            ),
        )

        val ids = result.items.map { it.property_id }
        assertFalse(ids.contains(1004L))
        assertEquals(listOf(1001L, 1003L, 1002L), ids)
    }
}
