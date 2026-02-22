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
class SearchFacetServiceTest {
    @Autowired
    lateinit var searchFacetService: SearchFacetService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setupSchemaAndData() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS city_poi_popular")
        jdbcTemplate.execute("DROP TABLE IF EXISTS district")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_brand")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_payment_option")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_theme")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_amenity")
        jdbcTemplate.execute("DROP TABLE IF EXISTS room_type")
        jdbcTemplate.execute("DROP TABLE IF EXISTS poi")
        jdbcTemplate.execute("DROP TABLE IF EXISTS brand")
        jdbcTemplate.execute("DROP TABLE IF EXISTS payment_option")
        jdbcTemplate.execute("DROP TABLE IF EXISTS theme")
        jdbcTemplate.execute("DROP TABLE IF EXISTS amenity")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_type")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property")

        jdbcTemplate.execute(
            """
            CREATE TABLE property (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              city VARCHAR(100),
              district_name VARCHAR(120),
              star_rating INT DEFAULT 4,
              location_rating DECIMAL(3,2) DEFAULT 0,
              status VARCHAR(20) NOT NULL,
              rating DECIMAL(3,2),
              property_type_code VARCHAR(40),
              kid_free_stay TINYINT(1) DEFAULT 0,
              is_beachfront TINYINT(1) DEFAULT 0,
              beach_distance_m INT NULL,
              lat DECIMAL(10,7),
              lng DECIMAL(10,7)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE property_type (
              code VARCHAR(40) PRIMARY KEY,
              label_ko VARCHAR(100) NOT NULL,
              label_en VARCHAR(100) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE payment_option (
              code VARCHAR(40) PRIMARY KEY,
              label_ko VARCHAR(100) NOT NULL,
              group_code VARCHAR(40) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE theme (
              code VARCHAR(40) PRIMARY KEY,
              label_ko VARCHAR(100) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE amenity (
              code VARCHAR(40) PRIMARY KEY,
              label_ko VARCHAR(100) NOT NULL,
              label_en VARCHAR(100) NOT NULL,
              group_code VARCHAR(40) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE brand (
              id BIGINT PRIMARY KEY,
              name VARCHAR(120) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE poi (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              city VARCHAR(100),
              category VARCHAR(50),
              active TINYINT(1) NOT NULL DEFAULT 1,
              popularity_score INT NOT NULL DEFAULT 0,
              lat DECIMAL(10,7),
              lng DECIMAL(10,7)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE room_type (
              id BIGINT PRIMARY KEY,
              property_id BIGINT NOT NULL,
              status VARCHAR(20) NOT NULL,
              bed_type VARCHAR(30),
              bedrooms INT DEFAULT 1
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE district (
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
            CREATE TABLE city_poi_popular (
              city VARCHAR(100) NOT NULL,
              poi_id BIGINT NOT NULL,
              rank_score INT NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE property_brand (
              property_id BIGINT NOT NULL,
              brand_id BIGINT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE property_payment_option (
              property_id BIGINT NOT NULL,
              payment_option_code VARCHAR(40) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE property_theme (
              property_id BIGINT NOT NULL,
              theme_code VARCHAR(40) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE property_amenity (
              property_id BIGINT NOT NULL,
              amenity_code VARCHAR(40) NOT NULL
            )
            """.trimIndent(),
        )

        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, district_name, star_rating, location_rating, status, rating, property_type_code, kid_free_stay, is_beachfront, beach_distance_m, lat, lng)
            VALUES (1001, 'Alpha Hotel', 'Seoul', 'Gangnam', 5, 4.6, 'ACTIVE', 4.8, 'hotel', 1, 0, 12000, 37.5000, 127.0300)
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, district_name, star_rating, location_rating, status, rating, property_type_code, kid_free_stay, is_beachfront, beach_distance_m, lat, lng)
            VALUES (1002, 'Bravo Stay', 'Seoul', 'Myeongdong', 4, 3.7, 'ACTIVE', 3.8, 'resort', 0, 0, 22000, 37.5600, 126.9900)
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, district_name, star_rating, location_rating, status, rating, property_type_code, kid_free_stay, is_beachfront, beach_distance_m, lat, lng)
            VALUES (1003, 'Charlie Beach', 'Busan', 'Haeundae', 5, 4.3, 'ACTIVE', 4.5, 'hotel', 1, 1, 150, 35.1689, 129.1287)
            """.trimIndent(),
        )

        jdbcTemplate.update("INSERT INTO district(id, city, name, blurb, rank_score) VALUES (1, 'Seoul', 'Gangnam', '비즈니스 중심지', 100)")
        jdbcTemplate.update("INSERT INTO district(id, city, name, blurb, rank_score) VALUES (2, 'Seoul', 'Myeongdong', '관광/쇼핑 중심지', 90)")

        jdbcTemplate.update("INSERT INTO property_type(code, label_ko, label_en) VALUES ('hotel', '호텔', 'Hotel')")
        jdbcTemplate.update("INSERT INTO property_type(code, label_ko, label_en) VALUES ('resort', '리조트', 'Resort')")
        jdbcTemplate.update("INSERT INTO property_type(code, label_ko, label_en) VALUES ('hostel', '호스텔', 'Hostel')")

        jdbcTemplate.update("INSERT INTO payment_option(code, label_ko, group_code) VALUES ('pay_now', '지금 바로 결제', 'timing')")
        jdbcTemplate.update("INSERT INTO payment_option(code, label_ko, group_code) VALUES ('free_cancel', '예약 무료 취소', 'policy')")
        jdbcTemplate.update("INSERT INTO payment_option(code, label_ko, group_code) VALUES ('no_credit_card', '신용카드 없이 예약 가능', 'policy')")

        jdbcTemplate.update("INSERT INTO theme(code, label_ko) VALUES ('family', '가족 여행객 친화형')")
        jdbcTemplate.update("INSERT INTO theme(code, label_ko) VALUES ('workation', '워케이션 친화형')")

        // legacy group_code도 보정되는지 확인하기 위해 essential/dining/room 사용
        jdbcTemplate.update("INSERT INTO amenity(code, label_ko, label_en, group_code) VALUES ('fridge', '냉장고', 'Refrigerator', 'room')")
        jdbcTemplate.update("INSERT INTO amenity(code, label_ko, label_en, group_code) VALUES ('internet', '인터넷', 'Internet', 'essential')")
        jdbcTemplate.update("INSERT INTO amenity(code, label_ko, label_en, group_code) VALUES ('breakfast', '조식 포함', 'Breakfast Included', 'dining')")

        jdbcTemplate.update("INSERT INTO brand(id, name) VALUES (1, 'Shilla')")
        jdbcTemplate.update("INSERT INTO brand(id, name) VALUES (2, 'Vista Signature')")

        jdbcTemplate.update("INSERT INTO property_brand(property_id, brand_id) VALUES (1001, 1)")
        jdbcTemplate.update("INSERT INTO property_payment_option(property_id, payment_option_code) VALUES (1001, 'pay_now')")
        jdbcTemplate.update("INSERT INTO property_payment_option(property_id, payment_option_code) VALUES (1001, 'free_cancel')")
        jdbcTemplate.update("INSERT INTO property_theme(property_id, theme_code) VALUES (1001, 'family')")
        jdbcTemplate.update("INSERT INTO property_amenity(property_id, amenity_code) VALUES (1001, 'fridge')")
        jdbcTemplate.update("INSERT INTO property_amenity(property_id, amenity_code) VALUES (1001, 'internet')")

        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, bed_type, bedrooms) VALUES (1, 1001, 'ACTIVE', 'DOUBLE', 1)")
        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, bed_type, bedrooms) VALUES (2, 1002, 'ACTIVE', 'TWIN', 2)")
        jdbcTemplate.update("INSERT INTO room_type(id, property_id, status, bed_type, bedrooms) VALUES (3, 1003, 'ACTIVE', 'KING', 3)")

        jdbcTemplate.update("INSERT INTO poi(id, name, city, category, active, popularity_score, lat, lng) VALUES (2001, 'N서울타워', 'Seoul', 'attraction', 1, 90, 37.5512, 126.9882)")
        jdbcTemplate.update("INSERT INTO poi(id, name, city, category, active, popularity_score, lat, lng) VALUES (2002, '명동거리', 'Seoul', 'shopping', 1, 80, 37.5638, 126.9850)")
        jdbcTemplate.update("INSERT INTO city_poi_popular(city, poi_id, rank_score) VALUES ('Busan', 2001, 50)")
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS city_poi_popular")
        jdbcTemplate.execute("DROP TABLE IF EXISTS district")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_brand")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_payment_option")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_theme")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_amenity")
        jdbcTemplate.execute("DROP TABLE IF EXISTS room_type")
        jdbcTemplate.execute("DROP TABLE IF EXISTS poi")
        jdbcTemplate.execute("DROP TABLE IF EXISTS brand")
        jdbcTemplate.execute("DROP TABLE IF EXISTS payment_option")
        jdbcTemplate.execute("DROP TABLE IF EXISTS theme")
        jdbcTemplate.execute("DROP TABLE IF EXISTS amenity")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_type")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property")
    }

    @Test
    fun `facets should expose taxonomy rows even when count is zero`() {
        val facets = searchFacetService.facets(placeId = "city:Seoul", city = null)

        assertTrue(facets.property_types.any { it.key == "hostel" && it.count == 0 })
        assertTrue(facets.payment_options.any { it.key == "no_credit_card" && it.count == 0 })
        assertTrue(facets.themes.any { it.key == "workation" && it.count == 0 })
        assertTrue(facets.stars.any { it.key == "1" })
        assertTrue(facets.bed_types.any { it.key == "BUNK" && it.count == 0 })
        assertTrue(facets.family_options.isNotEmpty())
        assertTrue(facets.beach_options.isNotEmpty())
    }

    @Test
    fun `facets should normalize korean city and fallback nearby attractions`() {
        val facets = searchFacetService.facets(placeId = null, city = "서울")

        assertFalse(facets.districts.isEmpty())
        assertTrue(facets.nearby_attractions.any { it.name.contains("서울") })
        assertFalse(facets.popular_filters.isEmpty())
    }

    @Test
    fun `facets should normalize legacy amenity group codes`() {
        val facets = searchFacetService.facets(placeId = "city:Seoul", city = null)

        val serviceGroup = facets.amenity_groups.firstOrNull { it.group == "service_option" }
        val roomGroup = facets.amenity_groups.firstOrNull { it.group == "room_facility" }
        val propertyGroup = facets.amenity_groups.firstOrNull { it.group == "property_facility" }

        assertTrue(serviceGroup?.items?.any { it.key == "breakfast" } == true)
        assertTrue(roomGroup?.items?.any { it.key == "fridge" } == true)
        assertTrue(propertyGroup?.items?.any { it.key == "internet" } == true)
        assertEquals("도심에 위치", facets.distance_bands.first().label)
    }
}
