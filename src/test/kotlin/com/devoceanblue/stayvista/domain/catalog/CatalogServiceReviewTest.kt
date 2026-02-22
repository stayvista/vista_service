package com.devoceanblue.stayvista.domain.catalog

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class CatalogServiceReviewTest {
    @Autowired
    lateinit var catalogService: CatalogService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_review_tag")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_review")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property")

        jdbcTemplate.execute(
            """
            CREATE TABLE property (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE property_review (
              id BIGINT PRIMARY KEY,
              property_id BIGINT NOT NULL,
              reviewer_name VARCHAR(120) NOT NULL,
              traveler_type VARCHAR(80) NOT NULL,
              stay_date DATE NOT NULL,
              score_overall DECIMAL(3,1) NOT NULL,
              score_service DECIMAL(3,1) NULL,
              score_cleanliness DECIMAL(3,1) NULL,
              score_facility DECIMAL(3,1) NULL,
              score_value DECIMAL(3,1) NULL,
              score_location DECIMAL(3,1) NULL,
              title VARCHAR(255) NOT NULL,
              body VARCHAR(1000) NOT NULL,
              status VARCHAR(20) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE property_review_tag (
              review_id BIGINT NOT NULL,
              tag VARCHAR(60) NOT NULL,
              PRIMARY KEY (review_id, tag)
            )
            """.trimIndent(),
        )

        jdbcTemplate.update("INSERT INTO property(id, name) VALUES (1001, 'Seoul Sample Hotel')")
        jdbcTemplate.update(
            """
            INSERT INTO property_review(
              id, property_id, reviewer_name, traveler_type, stay_date,
              score_overall, score_service, score_cleanliness, score_facility, score_value, score_location,
              title, body, status
            )
            VALUES (1, 1001, '민지', '가족 여행객', DATE '2026-01-02', 9.2, 9.4, 9.1, 8.9, 8.7, 9.0, '좋은 숙박', '아이와 함께 머물기 좋았습니다.', 'PUBLISHED')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO property_review(
              id, property_id, reviewer_name, traveler_type, stay_date,
              score_overall, score_service, score_cleanliness, score_facility, score_value, score_location,
              title, body, status
            )
            VALUES (2, 1001, '서연', '커플/2인 여행객', DATE '2026-01-01', 8.4, 8.5, 8.3, 8.2, 8.0, 8.1, '깔끔한 객실', '체크인 동선이 편리했습니다.', 'PUBLISHED')
            """.trimIndent(),
        )

        jdbcTemplate.update("INSERT INTO property_review_tag(review_id, tag) VALUES (1, '서비스')")
        jdbcTemplate.update("INSERT INTO property_review_tag(review_id, tag) VALUES (1, '조식')")
        jdbcTemplate.update("INSERT INTO property_review_tag(review_id, tag) VALUES (2, '서비스')")
        jdbcTemplate.update("INSERT INTO property_review_tag(review_id, tag) VALUES (2, '위치')")
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_review_tag")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property_review")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property")
    }

    @Test
    fun `listPropertyReviews should return summary and tags from DB`() {
        val result = catalogService.listPropertyReviews(
            propertyId = 1001L,
            tag = null,
            page = 1,
            size = 10,
        )

        assertEquals(2L, result.summary.total)
        assertEquals(8.8, result.summary.avg_score)
        assertEquals(2, result.items.size)
        assertEquals("민지", result.items.first().reviewer)
        assertTrue(result.tags.any { it.tag == "서비스" && it.count == 2L })
    }

    @Test
    fun `listPropertyReviews should apply tag filter to items`() {
        val result = catalogService.listPropertyReviews(
            propertyId = 1001L,
            tag = "조식",
            page = 1,
            size = 10,
        )

        assertEquals(1L, result.meta.total)
        assertEquals(1, result.items.size)
        assertEquals("민지", result.items.first().reviewer)
        assertEquals(2L, result.summary.total)
    }

    @Test
    fun `listPropertyReviews should throw not found when property does not exist`() {
        val ex = assertThrows<DomainException> {
            catalogService.listPropertyReviews(
                propertyId = 9999L,
                tag = null,
                page = 1,
                size = 10,
            )
        }

        assertEquals(ErrorCode.NOT_FOUND.code, ex.errorCode.code)
    }
}
