package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.domain.common.PlaceType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
    properties = [
        "stayvista.autocomplete.aggregate.enabled=false",
    ],
)
class AutocompletePopularRankingTest {
    @Autowired
    lateinit var autocompleteService: AutocompleteService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @MockitoBean
    lateinit var cacheService: AutocompleteCacheService

    @MockitoBean
    lateinit var openSearchGateway: AutocompleteOpenSearchGateway

    @BeforeEach
    fun setupSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS ac_suggest_metric")
        jdbcTemplate.execute("DROP TABLE IF EXISTS poi")
        jdbcTemplate.execute("DROP TABLE IF EXISTS property")

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS property (
              id BIGINT PRIMARY KEY,
              name VARCHAR(255) NOT NULL,
              city VARCHAR(100),
              status VARCHAR(20) NOT NULL,
              rating DECIMAL(3,2)
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
              lat DECIMAL(10,7),
              lng DECIMAL(10,7)
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
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.update("DELETE FROM ac_suggest_metric")
        jdbcTemplate.update("DELETE FROM poi")
        jdbcTemplate.update("DELETE FROM property")
    }

    @Test
    fun `empty query should rank popular cities by learned metric before raw count`() {
        insertProperty(id = 1001, name = "Busan Bay 1", city = "Busan", rating = 4.5)
        insertProperty(id = 1002, name = "Busan Bay 2", city = "Busan", rating = 4.4)
        insertProperty(id = 1003, name = "Busan Bay 3", city = "Busan", rating = 4.3)
        insertProperty(id = 1004, name = "Seoul Center", city = "Seoul", rating = 4.0)
        insertMetric(type = "CITY", canonicalId = "Seoul", impressions7d = 900, selects7d = 210, ctr7d = 0.23, popularity7d = 900)
        insertMetric(type = "CITY", canonicalId = "Busan", impressions7d = 150, selects7d = 12, ctr7d = 0.08, popularity7d = 150)

        given(cacheService.getRecent("anon:test")).willReturn(emptyList())
        given(cacheService.popularKey("ko", setOf(PlaceType.CITY), 2)).willReturn("ac:popular:city")
        given(cacheService.getSuggestions("ac:popular:city")).willReturn(null)

        val response = autocompleteService.autocomplete(
            AutocompleteQuery(
                q = "",
                types = setOf(PlaceType.CITY),
                size = 2,
                lang = "ko",
                principalKey = "anon:test",
            ),
        )

        assertEquals(listOf("city:Seoul", "city:Busan"), response.items.map { it.id })
    }

    @Test
    fun `empty query should rank popular properties by learned metric before rating`() {
        insertProperty(id = 2001, name = "Top Rated Hotel", city = "Seoul", rating = 4.9)
        insertProperty(id = 2002, name = "Learned Favorite Hotel", city = "Seoul", rating = 3.2)
        insertMetric(type = "PROPERTY", canonicalId = "2002", impressions7d = 700, selects7d = 224, ctr7d = 0.32, popularity7d = 700)
        insertMetric(type = "PROPERTY", canonicalId = "2001", impressions7d = 100, selects7d = 9, ctr7d = 0.09, popularity7d = 100)

        given(cacheService.getRecent("anon:test")).willReturn(emptyList())
        given(cacheService.popularKey("ko", setOf(PlaceType.PROPERTY), 2)).willReturn("ac:popular:property")
        given(cacheService.getSuggestions("ac:popular:property")).willReturn(null)

        val response = autocompleteService.autocomplete(
            AutocompleteQuery(
                q = "",
                types = setOf(PlaceType.PROPERTY),
                size = 2,
                lang = "ko",
                principalKey = "anon:test",
            ),
        )

        assertEquals(listOf("property:2002", "property:2001"), response.items.map { it.id })
    }

    @Test
    fun `empty query should rank popular pois by learned metric before id order`() {
        insertPoi(id = 3001, name = "Old Museum", category = "museum", city = "Seoul")
        insertPoi(id = 3002, name = "Click Hero", category = "landmark", city = "Seoul")
        insertMetric(type = "POI", canonicalId = "3002", impressions7d = 440, selects7d = 66, ctr7d = 0.15, popularity7d = 440)
        insertMetric(type = "POI", canonicalId = "3001", impressions7d = 120, selects7d = 6, ctr7d = 0.05, popularity7d = 120)

        given(cacheService.getRecent("anon:test")).willReturn(emptyList())
        given(cacheService.popularKey("ko", setOf(PlaceType.POI), 2)).willReturn("ac:popular:poi")
        given(cacheService.getSuggestions("ac:popular:poi")).willReturn(null)

        val response = autocompleteService.autocomplete(
            AutocompleteQuery(
                q = "",
                types = setOf(PlaceType.POI),
                size = 2,
                lang = "ko",
                principalKey = "anon:test",
            ),
        )

        assertEquals(listOf("poi:3002", "poi:3001"), response.items.map { it.id })
    }

    private fun insertProperty(
        id: Long,
        name: String,
        city: String,
        rating: Double,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO property(id, name, city, status, rating)
            VALUES (?, ?, ?, 'ACTIVE', ?)
            """.trimIndent(),
            id,
            name,
            city,
            rating,
        )
    }

    private fun insertPoi(
        id: Long,
        name: String,
        category: String,
        city: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO poi(id, name, category, city, lat, lng)
            VALUES (?, ?, ?, ?, 37.5000, 127.0000)
            """.trimIndent(),
            id,
            name,
            category,
            city,
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
