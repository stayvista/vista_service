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
        jdbcTemplate.execute("DROP TABLE IF EXISTS room_type")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property")
        jdbcTemplate.execute("DROP TABLE IF EXISTS poi")

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS property (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              city VARCHAR(100),
              district_name VARCHAR(120),
              star_rating INT DEFAULT 4,
              location_rating DECIMAL(3,2) DEFAULT 0,
              popularity_score INT DEFAULT 0,
              property_type_code VARCHAR(40),
              review_count INT DEFAULT 0,
              beach_distance_m INT NULL,
              is_beachfront TINYINT(1) DEFAULT 0,
              kid_free_stay TINYINT(1) DEFAULT 0,
              status VARCHAR(20) NOT NULL,
              rating DECIMAL(3,2),
              thumbnail_url VARCHAR(255),
              lat DECIMAL(10,7),
              lng DECIMAL(10,7)
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
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS poi (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              category VARCHAR(50),
              city VARCHAR(100),
              active TINYINT(1) NOT NULL DEFAULT 1,
              lat DECIMAL(10,7) NOT NULL,
              lng DECIMAL(10,7) NOT NULL
            )
            """.trimIndent(),
        )

        jdbcTemplate.update("DELETE FROM room_type")
        jdbcTemplate.update("DELETE FROM property")
        jdbcTemplate.update("DELETE FROM poi")

        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, district_name, star_rating, location_rating, popularity_score, property_type_code, review_count, beach_distance_m, is_beachfront, kid_free_stay, status, rating, thumbnail_url, lat, lng)
            VALUES (1001, 'Alpha Hotel', 'Seoul', 'Gangnam', 5, 4.4, 880, 'hotel', 1200, 18000, 0, 1, 'ACTIVE', 4.8, 'https://img/alpha', 37.5000, 127.0300)
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, district_name, star_rating, location_rating, popularity_score, property_type_code, review_count, beach_distance_m, is_beachfront, kid_free_stay, status, rating, thumbnail_url, lat, lng)
            VALUES (1002, 'Bravo Stay', 'Seoul', 'Myeongdong', 4, 3.9, 620, 'boutique', 420, 21000, 0, 0, 'ACTIVE', 3.9, 'https://img/bravo', 37.5600, 126.9900)
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, district_name, star_rating, location_rating, popularity_score, property_type_code, review_count, beach_distance_m, is_beachfront, kid_free_stay, status, rating, thumbnail_url, lat, lng)
            VALUES (1003, 'Charlie House', 'Busan', 'Haeundae', 5, 4.2, 700, 'resort', 980, 350, 1, 1, 'ACTIVE', 4.5, 'https://img/charlie', 35.1700, 129.1300)
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, district_name, star_rating, location_rating, popularity_score, property_type_code, review_count, beach_distance_m, is_beachfront, kid_free_stay, status, rating, thumbnail_url, lat, lng)
            VALUES (1004, 'Dormant Inn', 'Seoul', 'Yeouido', 5, 4.6, 400, 'hotel', 850, 17000, 0, 0, 'INACTIVE', 4.9, 'https://img/inactive', 37.5200, 126.9300)
            """.trimIndent(),
        )

        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, base_price) VALUES (2001, 1001, 'ACTIVE', 180000)")
        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, base_price) VALUES (2002, 1002, 'ACTIVE', 90000)")
        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, base_price) VALUES (2003, 1003, 'ACTIVE', 150000)")
        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, base_price) VALUES (2004, 1001, 'INACTIVE', 50000)")
        jdbcTemplate.update("INSERT INTO poi(id, name, category, city, active, lat, lng) VALUES (3001, 'Busan Harbor Point', 'poi', 'Busan', 1, 35.1689, 129.1287)")
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.update("DELETE FROM room_type")
        jdbcTemplate.update("DELETE FROM property")
        jdbcTemplate.update("DELETE FROM poi")
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

    @Test
    fun `search should honor property place_id over city filter`() {
        val result = searchService.search(
            SearchRequest(
                q = null,
                city = "Seoul",
                place_id = "property:1003",
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
            ),
        )

        assertEquals(1, result.items.size)
        assertEquals(1003L, result.items.first().property_id)
    }

    @Test
    fun `search should resolve poi place_id into city filter`() {
        val result = searchService.search(
            SearchRequest(
                q = null,
                city = null,
                place_id = "poi:3001",
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
            ),
        )

        assertEquals(1, result.items.size)
        assertEquals(1003L, result.items.first().property_id)
        assertEquals("Busan", result.items.first().city)
    }

    @Test
    fun `search should apply family and beach filters together`() {
        val result = searchService.search(
            SearchRequest(
                q = null,
                city = "Busan",
                check_in = null,
                check_out = null,
                adults = null,
                children = null,
                min_price = null,
                max_price = null,
                min_rating = null,
                family_options = listOf("kid_free_stay"),
                beach_options = listOf("beach_nearby"),
                sort = null,
                cursor = null,
                limit = 20,
            ),
        )

        assertEquals(1, result.items.size)
        assertEquals(1003L, result.items.first().property_id)
    }

    @Test
    fun `search should filter by nearby attractions`() {
        val result = searchService.search(
            SearchRequest(
                q = null,
                city = "Busan",
                check_in = null,
                check_out = null,
                adults = null,
                children = null,
                min_price = null,
                max_price = null,
                min_rating = null,
                nearby_attractions = listOf(3001L),
                sort = "distance",
                cursor = null,
                limit = 20,
            ),
        )

        assertEquals(1, result.items.size)
        assertEquals(1003L, result.items.first().property_id)
    }
}
