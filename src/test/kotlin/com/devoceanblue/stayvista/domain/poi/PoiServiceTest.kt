package com.devoceanblue.stayvista.domain.poi

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class PoiServiceTest {
    @Autowired
    lateinit var poiService: PoiService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setupSchemaAndData() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS poi (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              category VARCHAR(50) NULL,
              city VARCHAR(100) NULL,
              lat DECIMAL(10, 7) NOT NULL,
              lng DECIMAL(10, 7) NOT NULL,
              active TINYINT(1) NOT NULL DEFAULT 1,
              address VARCHAR(255) NULL,
              description VARCHAR(1000) NULL,
              image_urls CLOB NULL,
              popularity_score INT NOT NULL DEFAULT 0,
              rating_score DECIMAL(3, 2) NOT NULL DEFAULT 0.0,
              geohash VARCHAR(16) NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS property (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              city VARCHAR(100) NULL,
              rating DECIMAL(3, 2) NOT NULL DEFAULT 0.0,
              thumbnail_url VARCHAR(500) NULL,
              lat DECIMAL(10, 7) NULL,
              lng DECIMAL(10, 7) NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS product (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              product_type VARCHAR(30) NOT NULL,
              city VARCHAR(100) NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS outbox_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              event_id VARCHAR(50) NOT NULL,
              aggregate_type VARCHAR(50) NOT NULL,
              aggregate_id VARCHAR(100) NOT NULL,
              event_type VARCHAR(50) NOT NULL,
              payload_json CLOB NOT NULL,
              status VARCHAR(20) NOT NULL,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP()
            )
            """.trimIndent(),
        )

        jdbcTemplate.update("DELETE FROM outbox_event")
        jdbcTemplate.update("DELETE FROM poi")
        jdbcTemplate.update("DELETE FROM property")
        jdbcTemplate.update("DELETE FROM product")

        insertPoi(
            id = 9101,
            name = "City Museum",
            category = "museum",
            city = "Seoul",
            lat = 37.5002,
            lng = 127.0401,
            active = true,
            popularity = 400,
            rating = 4.4,
            description = "Cultural landmark",
            imageUrls = "[\"https://img.test/museum-1.jpg\"]",
        )
        insertPoi(
            id = 9102,
            name = "Food Alley",
            category = "food",
            city = "Seoul",
            lat = 37.5010,
            lng = 127.0390,
            active = true,
            popularity = 900,
            rating = 4.8,
            description = "Best local dishes",
            imageUrls = "[\"https://img.test/food-1.jpg\",\"https://img.test/food-2.jpg\"]",
        )
        insertPoi(
            id = 9105,
            name = "Late Night Bites",
            category = "food",
            city = "Seoul",
            lat = 37.5030,
            lng = 127.0410,
            active = true,
            popularity = 760,
            rating = 4.5,
            description = "Open until midnight",
            imageUrls = "[\"https://img.test/food-3.jpg\"]",
        )
        insertPoi(
            id = 9103,
            name = "Hidden Spot",
            category = "food",
            city = "Seoul",
            lat = 37.5021,
            lng = 127.0380,
            active = false,
            popularity = 800,
            rating = 4.7,
            description = "Private location",
            imageUrls = "[]",
        )
        insertPoiWithoutGeohash(
            id = 9104,
            name = "No Hash Place",
            category = "attraction",
            city = "Seoul",
            lat = 37.4992,
            lng = 127.0388,
        )

        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, rating, thumbnail_url, lat, lng, status)
            VALUES (1001, 'Wanderly Hotel Seoul', 'Seoul', 4.6, 'https://img.test/hotel.jpg', 37.5005, 127.0399, 'ACTIVE')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO product(id, name, product_type, city, status)
            VALUES (2001, 'City Pass', 'ATTRACTION', 'Seoul', 'ACTIVE')
            """.trimIndent(),
        )
    }

    @Test
    fun `nearby should return active items only with pagination metadata`() {
        val data = poiService.nearby(
            PoiNearbyQuery(
                bbox = PoiBoundingBox.parse("37.49,127.03,37.51,127.05"),
                category = "food",
                limit = 1,
                offset = 0,
                sort = PoiSort.POPULARITY,
                center = PoiCenter.parse("37.5000,127.0400"),
                radius_m = null,
            ),
        )

        assertEquals(1, data.items.size)
        assertTrue(data.meta.has_more)
        assertEquals(9102L, data.items.first().id)
    }

    @Test
    fun `detail should include links and related hints`() {
        val detail = poiService.getPoiDetail(9102)

        assertEquals(9102L, detail.id)
        assertEquals(2, detail.images.size)
        assertTrue(detail.links.google.contains("google.com/maps"))
        assertTrue(detail.links.naver.contains("map.naver.com"))
        assertEquals(1, detail.related.properties.size)
        assertEquals(1, detail.related.products.size)
    }

    @Test
    fun `detail should throw not found for inactive poi`() {
        val exception = assertThrows<DomainException> {
            poiService.getPoiDetail(9103)
        }

        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `patch active false should remove poi from nearby`() {
        poiService.patchAdminPoi(
            poiId = 9102,
            request = AdminPoiPatchRequest(active = false),
        )

        val data = poiService.nearby(
            PoiNearbyQuery(
                bbox = PoiBoundingBox.parse("37.49,127.03,37.51,127.05"),
                category = "food",
                limit = 10,
                offset = 0,
                sort = PoiSort.DISTANCE,
                center = PoiCenter.parse("37.5000,127.0400"),
                radius_m = null,
            ),
        )

        assertFalse(data.items.any { it.id == 9102L })
    }

    @Test
    fun `backfill geohash should populate rows without geohash`() {
        val result = poiService.backfillGeohash(limit = 10)
        assertTrue(result.scanned >= 1)
        assertTrue(result.updated >= 1)

        val geohash = jdbcTemplate.queryForObject(
            "SELECT geohash FROM poi WHERE id = ?",
            String::class.java,
            9104L,
        )
        assertNotNull(geohash)
        assertTrue((geohash ?: "").isNotBlank())
    }

    private fun insertPoi(
        id: Long,
        name: String,
        category: String,
        city: String,
        lat: Double,
        lng: Double,
        active: Boolean,
        popularity: Int,
        rating: Double,
        description: String,
        imageUrls: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO poi(
              id, name, category, city, lat, lng, active, address, description,
              image_urls, popularity_score, rating_score, geohash
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            name,
            category,
            city,
            lat.toBigDecimal(),
            lng.toBigDecimal(),
            active,
            "$city Center",
            description,
            imageUrls,
            popularity,
            rating.toBigDecimal(),
            PoiGeohash.encode(lat, lng, 9),
        )
    }

    private fun insertPoiWithoutGeohash(
        id: Long,
        name: String,
        category: String,
        city: String,
        lat: Double,
        lng: Double,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO poi(
              id, name, category, city, lat, lng, active, address, description,
              image_urls, popularity_score, rating_score, geohash
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            name,
            category,
            city,
            lat.toBigDecimal(),
            lng.toBigDecimal(),
            true,
            "$city Center",
            "Pending geohash",
            "[]",
            300,
            4.2.toBigDecimal(),
            null,
        )
    }
}
