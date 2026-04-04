package com.devoceanblue.stayvista.domain.catalog

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Service

@Service
class HomeContentService(
    private val mapper: HomeContentMapper,
) {
    fun getContent(): HomeContentData {
        val heroRow = mapper.findHeroRow()

        val heroMetrics = if (heroRow == null) {
            emptyList()
        } else {
            mapper.listHeroMetrics(heroRow.id).map {
                HomeHeroMetricData(
                    metric_value = it.metricValue,
                    metric_label = it.metricLabel,
                )
            }
        }

        val quickFilters = mapper.listQuickFilters().map {
            HomeQuickFilterData(
                label = it.label,
                filter_key = it.filterKey,
                filter_value = it.filterValue,
            )
        }

        val propertyCountByCity = mapper.listPropertyCountByCity().associate { it.city to it.count.toInt() }

        val destinationRows = mapper.listDestinationRows()

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

        val promotionSections = mapper.listPromotionSections().map {
            HomePromotionSectionData(
                section_code = it.sectionCode,
                title = it.title,
                subtitle = it.subtitle,
            )
        }

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

data class HomeHeroMetricRow(
    val metricValue: String,
    val metricLabel: String,
)

data class HomeQuickFilterData(
    val label: String,
    val filter_key: String,
    val filter_value: String,
)

data class HomeQuickFilterRow(
    val label: String,
    val filterKey: String,
    val filterValue: String,
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

data class HomePromotionSectionRow(
    val sectionCode: String,
    val title: String,
    val subtitle: String?,
)

data class HomeHeroRow(
    val id: Int,
    val eyebrowText: String,
    val titleText: String,
    val summaryText: String,
    val backgroundImageUrl: String?,
)

data class HomeDestinationCardRow(
    val sectionCode: String,
    val city: String,
    val country: String?,
    val label: String,
    val imageUrl: String?,
    val highlights: String?,
    val propertyCount: Int?,
)

data class HomeCityCountRow(
    val city: String,
    val count: Long,
)

@Mapper
interface HomeContentMapper {
    @Select(
        """
        SELECT id,
               eyebrow_text AS eyebrowText,
               title_text AS titleText,
               summary_text AS summaryText,
               background_image_url AS backgroundImageUrl
        FROM home_hero
        WHERE active = 1
        ORDER BY id ASC
        LIMIT 1
        """,
    )
    fun findHeroRow(): HomeHeroRow?

    @Select(
        """
        SELECT metric_value AS metricValue,
               metric_label AS metricLabel
        FROM home_hero_metric
        WHERE hero_id = #{heroId}
          AND active = 1
        ORDER BY display_order ASC, id ASC
        """,
    )
    fun listHeroMetrics(@Param("heroId") heroId: Int): List<HomeHeroMetricRow>

    @Select(
        """
        SELECT label,
               filter_key AS filterKey,
               filter_value AS filterValue
        FROM home_quick_filter
        WHERE active = 1
        ORDER BY display_order ASC, id ASC
        """,
    )
    fun listQuickFilters(): List<HomeQuickFilterRow>

    @Select(
        """
        SELECT city, COUNT(*) AS count
        FROM property
        WHERE status = 'ACTIVE'
          AND city IS NOT NULL
          AND city <> ''
        GROUP BY city
        """,
    )
    fun listPropertyCountByCity(): List<HomeCityCountRow>

    @Select(
        """
        SELECT section_code AS sectionCode,
               city,
               country,
               label,
               image_url AS imageUrl,
               highlights,
               property_count AS propertyCount
        FROM home_destination_card
        WHERE active = 1
        ORDER BY section_code ASC, display_order ASC, id ASC
        """,
    )
    fun listDestinationRows(): List<HomeDestinationCardRow>

    @Select(
        """
        SELECT section_code AS sectionCode,
               title,
               subtitle
        FROM promotion_section
        WHERE active = 1
        ORDER BY display_order ASC, section_code ASC
        """,
    )
    fun listPromotionSections(): List<HomePromotionSectionRow>
}
