package com.devoceanblue.stayvista.domain.catalog

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class HomeContentService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun getContent(): HomeContentData {
        val heroRow = jdbcTemplate.query(
            """
            SELECT id, eyebrow_text, title_text, summary_text, background_image_url
            FROM home_hero
            WHERE active = 1
            ORDER BY id ASC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                HomeHeroRow(
                    id = rs.getInt("id"),
                    eyebrowText = rs.getString("eyebrow_text"),
                    titleText = rs.getString("title_text"),
                    summaryText = rs.getString("summary_text"),
                    backgroundImageUrl = rs.getString("background_image_url"),
                )
            },
        ).firstOrNull()

        val heroMetrics = if (heroRow == null) {
            emptyList()
        } else {
            jdbcTemplate.query(
                """
                SELECT metric_value, metric_label
                FROM home_hero_metric
                WHERE hero_id = ?
                  AND active = 1
                ORDER BY display_order ASC, id ASC
                """.trimIndent(),
                { rs, _ ->
                    HomeHeroMetricData(
                        metric_value = rs.getString("metric_value"),
                        metric_label = rs.getString("metric_label"),
                    )
                },
                heroRow.id,
            )
        }

        val quickFilters = jdbcTemplate.query(
            """
            SELECT label, filter_key, filter_value
            FROM home_quick_filter
            WHERE active = 1
            ORDER BY display_order ASC, id ASC
            """.trimIndent(),
            { rs, _ ->
                HomeQuickFilterData(
                    label = rs.getString("label"),
                    filter_key = rs.getString("filter_key"),
                    filter_value = rs.getString("filter_value"),
                )
            },
        )

        val propertyCountByCity = jdbcTemplate.query(
            """
            SELECT city, COUNT(*) AS cnt
            FROM property
            WHERE status = 'ACTIVE'
              AND city IS NOT NULL
              AND city <> ''
            GROUP BY city
            """.trimIndent(),
            { rs, _ ->
                rs.getString("city") to rs.getInt("cnt")
            },
        ).toMap()

        val destinationRows = jdbcTemplate.query(
            """
            SELECT section_code, city, country, label, image_url, highlights, property_count
            FROM home_destination_card
            WHERE active = 1
            ORDER BY section_code ASC, display_order ASC, id ASC
            """.trimIndent(),
            { rs, _ ->
                HomeDestinationCardRow(
                    sectionCode = rs.getString("section_code"),
                    city = rs.getString("city"),
                    country = rs.getString("country"),
                    label = rs.getString("label"),
                    imageUrl = rs.getString("image_url"),
                    highlights = rs.getString("highlights"),
                    propertyCount = rs.getInt("property_count").takeIf { !rs.wasNull() },
                )
            },
        )

        val destinationSections = destinationRows
            .groupBy { it.sectionCode }
            .entries
            .sortedBy { it.key }
            .map { (sectionCode, rows) ->
                HomeDestinationSectionData(
                    section_code = sectionCode,
                    items = rows.map { row ->
                        HomeDestinationCardData(
                            city = row.city,
                            country = row.country,
                            label = row.label,
                            image_url = row.imageUrl,
                            highlights = row.highlights,
                            property_count = row.propertyCount ?: propertyCountByCity[row.city] ?: 0,
                        )
                    },
                )
            }

        val promotionSections = jdbcTemplate.query(
            """
            SELECT section_code, title, subtitle
            FROM promotion_section
            WHERE active = 1
            ORDER BY display_order ASC, section_code ASC
            """.trimIndent(),
            { rs, _ ->
                HomePromotionSectionData(
                    section_code = rs.getString("section_code"),
                    title = rs.getString("title"),
                    subtitle = rs.getString("subtitle"),
                )
            },
        )

        return HomeContentData(
            hero = heroRow?.let {
                HomeHeroData(
                    eyebrow_text = it.eyebrowText,
                    title_text = it.titleText,
                    summary_text = it.summaryText,
                    background_image_url = it.backgroundImageUrl,
                    metrics = heroMetrics,
                )
            },
            quick_filters = quickFilters,
            destination_sections = destinationSections,
            promotion_sections = promotionSections,
        )
    }
}

data class HomeContentData(
    val hero: HomeHeroData?,
    val quick_filters: List<HomeQuickFilterData>,
    val destination_sections: List<HomeDestinationSectionData>,
    val promotion_sections: List<HomePromotionSectionData>,
)

data class HomeHeroData(
    val eyebrow_text: String,
    val title_text: String,
    val summary_text: String,
    val background_image_url: String?,
    val metrics: List<HomeHeroMetricData>,
)

data class HomeHeroMetricData(
    val metric_value: String,
    val metric_label: String,
)

data class HomeQuickFilterData(
    val label: String,
    val filter_key: String,
    val filter_value: String,
)

data class HomeDestinationSectionData(
    val section_code: String,
    val items: List<HomeDestinationCardData>,
)

data class HomeDestinationCardData(
    val city: String,
    val country: String?,
    val label: String,
    val image_url: String?,
    val highlights: String?,
    val property_count: Int,
)

data class HomePromotionSectionData(
    val section_code: String,
    val title: String,
    val subtitle: String?,
)

private data class HomeHeroRow(
    val id: Int,
    val eyebrowText: String,
    val titleText: String,
    val summaryText: String,
    val backgroundImageUrl: String?,
)

private data class HomeDestinationCardRow(
    val sectionCode: String,
    val city: String,
    val country: String?,
    val label: String,
    val imageUrl: String?,
    val highlights: String?,
    val propertyCount: Int?,
)
