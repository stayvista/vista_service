package com.devoceanblue.stayvista.domain.poi

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PoiGeohashPrefixPlannerTest {
    @Test
    fun `resolvePrefixes should keep center prefix when planner saturates`() {
        val planner = PoiGeohashPrefixPlanner(maxPrefixes = 3)
        val bbox = PoiBoundingBox.parse("33.495120,124.376136,38.736020,130.805613")

        val prefixes = planner.resolvePrefixes(bbox)
        val centerPrefix = PoiGeohash.encode(bbox.centerLat(), bbox.centerLng(), 9).take(3)

        assertTrue(prefixes.size <= 3)
        assertTrue(prefixes.contains(centerPrefix))
    }
}
