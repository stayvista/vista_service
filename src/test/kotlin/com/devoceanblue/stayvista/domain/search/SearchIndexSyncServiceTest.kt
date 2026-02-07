package com.devoceanblue.stayvista.domain.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest
class SearchIndexSyncServiceTest {
    @Autowired
    lateinit var searchIndexSyncService: SearchIndexSyncService

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
              city VARCHAR(100) NULL,
              status VARCHAR(20) NOT NULL,
              rating DECIMAL(3, 2) NOT NULL,
              thumbnail_url VARCHAR(500) NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute("ALTER TABLE property ADD COLUMN IF NOT EXISTS country VARCHAR(100)")
        jdbcTemplate.execute("ALTER TABLE property ADD COLUMN IF NOT EXISTS lat DECIMAL(10, 7)")
        jdbcTemplate.execute("ALTER TABLE property ADD COLUMN IF NOT EXISTS lng DECIMAL(10, 7)")
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
        jdbcTemplate.execute("ALTER TABLE room_type ADD COLUMN IF NOT EXISTS name VARCHAR(255)")
        jdbcTemplate.execute("ALTER TABLE room_type ADD COLUMN IF NOT EXISTS capacity_adults INT DEFAULT 0")

        jdbcTemplate.update("DELETE FROM room_type")
        jdbcTemplate.update("DELETE FROM property")

        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, country, status, lat, lng, rating, thumbnail_url)
            VALUES (1001, 'Alpha Hotel', 'Seoul', 'KR', 'ACTIVE', 37.5, 127.0, 4.7, 'https://img/alpha')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, country, status, lat, lng, rating, thumbnail_url)
            VALUES (1002, 'Bravo Hotel', 'Busan', 'KR', 'ACTIVE', 35.1, 129.1, 4.3, 'https://img/bravo')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO room_type(id, property_id, name, capacity_adults, status, base_price)
            VALUES (2001, 1001, 'Standard', 2, 'ACTIVE', 120000)
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO room_type(id, property_id, name, capacity_adults, status, base_price)
            VALUES (2002, 1002, 'Deluxe', 3, 'ACTIVE', 150000)
            """.trimIndent(),
        )
    }

    @Test
    fun `reindexAll should upsert selected property documents`() {
        val result = searchIndexSyncService.reindexAll(limit = 1)

        assertEquals(1, result.scanned)
        assertEquals(1, result.upserted)
        assertEquals(0, result.failed)
        then(openSearchClient).should().upsertProperty(eq(1001L), anyMap())
    }
}
