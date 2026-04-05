package com.devoceanblue.stayvista.domain.search

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class DestinationRecommendationServiceTest {
    @Autowired
    lateinit var destinationRecommendationService: DestinationRecommendationService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setupSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS city_featured_property")
        jdbcTemplate.execute("DROP TABLE IF EXISTS city_poi_popular")
        jdbcTemplate.execute("DROP TABLE IF EXISTS district")
        jdbcTemplate.execute("DROP TABLE IF EXISTS ac_suggest_metric")
        jdbcTemplate.execute("DROP TABLE IF EXISTS poi")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property")

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS property (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              country VARCHAR(20),
              city VARCHAR(100),
              district_name VARCHAR(120),
              status VARCHAR(20) NOT NULL,
              thumbnail_url VARCHAR(255),
              star_rating INT NOT NULL DEFAULT 4,
              popularity_score INT NOT NULL DEFAULT 0,
              rating DECIMAL(3,2) NOT NULL DEFAULT 0.0
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
              popularity_score INT NOT NULL DEFAULT 0,
              rating_score DECIMAL(3,2) NOT NULL DEFAULT 0.0
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS ac_suggest_metric (
              type VARCHAR(20) NOT NULL,
              canonical_id VARCHAR(120) NOT NULL,
              impressions_7d BIGINT NOT NULL DEFAULT 0,
              selects_7d BIGINT NOT NULL DEFAULT 0,
              ctr_7d DOUBLE NOT NULL DEFAULT 0,
              popularity_7d BIGINT NOT NULL DEFAULT 0,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
              PRIMARY KEY (type, canonical_id)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS district (
              id BIGINT PRIMARY KEY,
              city VARCHAR(100) NOT NULL,
              name VARCHAR(120) NOT NULL,
              blurb VARCHAR(255),
              rank_score INT NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS city_poi_popular (
              city VARCHAR(100) NOT NULL,
              poi_id BIGINT NOT NULL,
              rank_score INT NOT NULL DEFAULT 0,
              PRIMARY KEY (city, poi_id)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS city_featured_property (
              city VARCHAR(100) NOT NULL,
              property_id BIGINT NOT NULL,
              rank_score INT NOT NULL DEFAULT 0,
              PRIMARY KEY (city, property_id)
            )
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.update("DELETE FROM city_featured_property")
        jdbcTemplate.update("DELETE FROM city_poi_popular")
        jdbcTemplate.update("DELETE FROM district")
        jdbcTemplate.update("DELETE FROM ac_suggest_metric")
        jdbcTemplate.update("DELETE FROM poi")
        jdbcTemplate.update("DELETE FROM property")
    }

    @Test
    fun `recommend should rank fallback featured properties by learned metric before rating`() {
        insertProperty(id = 1001, name = "Top Rated Hotel", country = "KR", city = "Seoul", district = "Gangnam", rating = 4.9, popularityScore = 100)
        insertProperty(id = 1002, name = "Learned Favorite Hotel", country = "KR", city = "Seoul", district = "Jongno", rating = 3.4, popularityScore = 80)
        insertMetric(type = "PROPERTY", canonicalId = "1002", impressions7d = 600, selects7d = 180, ctr7d = 0.30, popularity7d = 600)
        insertMetric(type = "PROPERTY", canonicalId = "1001", impressions7d = 120, selects7d = 12, ctr7d = 0.10, popularity7d = 120)

        val result = destinationRecommendationService.recommend(
            cityId = "Seoul",
            placeId = null,
            lang = "ko",
            limit = 4,
        )

        assertEquals(listOf(1002L, 1001L), result.featured_properties.take(2).map { it.property_id })
    }

    @Test
    fun `recommend should rank curated pois by learned metric when rank score ties`() {
        insertProperty(id = 9001, name = "Seed Hotel", country = "KR", city = "Busan", district = "Haeundae", rating = 4.2, popularityScore = 10)
        insertPoi(id = 2001, name = "Museum Walk", category = "museum", city = "Busan", popularityScore = 30, ratingScore = 4.5)
        insertPoi(id = 2002, name = "Harbor Night View", category = "attraction", city = "Busan", popularityScore = 20, ratingScore = 4.2)
        jdbcTemplate.update("INSERT INTO city_poi_popular(city, poi_id, rank_score) VALUES ('Busan', 2001, 50)")
        jdbcTemplate.update("INSERT INTO city_poi_popular(city, poi_id, rank_score) VALUES ('Busan', 2002, 50)")
        insertMetric(type = "POI", canonicalId = "2002", impressions7d = 500, selects7d = 70, ctr7d = 0.14, popularity7d = 500)
        insertMetric(type = "POI", canonicalId = "2001", impressions7d = 140, selects7d = 8, ctr7d = 0.05, popularity7d = 140)

        val result = destinationRecommendationService.recommend(
            cityId = "Busan",
            placeId = null,
            lang = "ko",
            limit = 4,
        )

        assertEquals(listOf(2002L, 2001L), result.pois.take(2).map { it.poi_id })
    }

    @Test
    fun `recommend should rank country popular cities by learned metric before property count`() {
        insertProperty(id = 3001, name = "Busan Bay 1", country = "KR", city = "Busan", district = "Suyeong", rating = 4.1, popularityScore = 50)
        insertProperty(id = 3002, name = "Busan Bay 2", country = "KR", city = "Busan", district = "Suyeong", rating = 4.0, popularityScore = 50)
        insertProperty(id = 3003, name = "Busan Bay 3", country = "KR", city = "Busan", district = "Haeundae", rating = 4.2, popularityScore = 50)
        insertProperty(id = 3004, name = "Seoul Center", country = "KR", city = "Seoul", district = "Jongno", rating = 4.3, popularityScore = 50)
        insertProperty(id = 3005, name = "Daegu Place", country = "KR", city = "Daegu", district = "Suseong", rating = 4.0, popularityScore = 50)
        insertMetric(type = "CITY", canonicalId = "Seoul", impressions7d = 900, selects7d = 210, ctr7d = 0.23, popularity7d = 900)
        insertMetric(type = "CITY", canonicalId = "Busan", impressions7d = 180, selects7d = 18, ctr7d = 0.10, popularity7d = 180)
        insertMetric(type = "CITY", canonicalId = "Daegu", impressions7d = 150, selects7d = 12, ctr7d = 0.08, popularity7d = 150)

        val result = destinationRecommendationService.recommend(
            cityId = "Jeju",
            placeId = null,
            lang = "ko",
            limit = 4,
        )

        assertEquals(listOf("Seoul", "Busan", "Daegu"), result.country_popular_cities.take(3).map { it.city })
    }

    private fun insertProperty(
        id: Long,
        name: String,
        country: String,
        city: String,
        district: String,
        rating: Double,
        popularityScore: Int,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, country, city, district_name, status, thumbnail_url, star_rating, popularity_score, rating)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'https://img.test/property.jpg', 5, ?, ?)
            """.trimIndent(),
            id,
            name,
            country,
            city,
            district,
            popularityScore,
            rating,
        )
    }

    private fun insertPoi(
        id: Long,
        name: String,
        category: String,
        city: String,
        popularityScore: Int,
        ratingScore: Double,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO poi(id, name, category, city, active, popularity_score, rating_score)
            VALUES (?, ?, ?, ?, 1, ?, ?)
            """.trimIndent(),
            id,
            name,
            category,
            city,
            popularityScore,
            ratingScore,
        )
    }

    private fun insertMetric(
        type: String,
        canonicalId: String,
        impressions7d: Long,
        selects7d: Long,
        ctr7d: Double,
        popularity7d: Long,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO ac_suggest_metric(type, canonical_id, impressions_7d, selects_7d, ctr_7d, popularity_7d)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            type,
            canonicalId,
            impressions7d,
            selects7d,
            ctr7d,
            popularity7d,
        )
    }
}
