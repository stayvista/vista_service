package com.devoceanblue.stayvista.domain.poi

import kotlin.math.ceil
import kotlin.math.max
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

object PoiGeohash {
    private val bits = intArrayOf(16, 8, 4, 2, 1)
    private val base32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray()

    fun encode(lat: Double, lng: Double, precision: Int = 12): String {
        require(precision in 1..12) { "precision must be in 1..12" }
        require(lat in -90.0..90.0) { "lat out of range" }
        require(lng in -180.0..180.0) { "lng out of range" }

        var isEven = true
        var bit = 0
        var ch = 0
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        val hash = StringBuilder(precision)

        val normalizedLng = normalizeLng(lng)
        while (hash.length < precision) {
            if (isEven) {
                val mid = (lngMin + lngMax) / 2.0
                if (normalizedLng >= mid) {
                    ch = ch or bits[bit]
                    lngMin = mid
                } else {
                    lngMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2.0
                if (lat >= mid) {
                    ch = ch or bits[bit]
                    latMin = mid
                } else {
                    latMax = mid
                }
            }

            isEven = !isEven
            if (bit < 4) {
                bit += 1
            } else {
                hash.append(base32[ch])
                bit = 0
                ch = 0
            }
        }
        return hash.toString()
    }

    private fun normalizeLng(value: Double): Double {
        if (value == 180.0) return 179.999999
        if (value == -180.0) return -179.999999
        return value
    }
}

@Component
class PoiGeohashPrefixPlanner(
    @Value("\${stayvista.poi.geohash.max-prefixes:24}") private val maxPrefixes: Int,
) {
    fun resolvePrefixes(bbox: PoiBoundingBox): List<String> {
        val prefixLength = pickPrefixLength(bbox)
        val latSteps = sampleSteps(bbox.latSpan(), prefixLength)
        val lngSteps = sampleSteps(bbox.lngSpan(), prefixLength)

        val prefixes = linkedSetOf<String>()
        for (latStep in 0..latSteps) {
            val lat = bbox.swLat + (bbox.latSpan() * latStep / latSteps)
            for (lngStep in 0..lngSteps) {
                val lng = bbox.swLng + (bbox.lngSpan() * lngStep / lngSteps)
                prefixes += PoiGeohash.encode(lat, lng, 9).take(prefixLength)
                if (prefixes.size >= maxPrefixes) {
                    return prefixes.toList()
                }
            }
        }

        // Safety: include center prefix to avoid edge-only samples.
        prefixes += PoiGeohash.encode(bbox.centerLat(), bbox.centerLng(), 9).take(prefixLength)
        return prefixes.toList().take(maxPrefixes)
    }

    private fun pickPrefixLength(bbox: PoiBoundingBox): Int {
        val areaHint = bbox.areaHint()
        return when {
            areaHint >= 25.0 -> 3
            areaHint >= 4.0 -> 4
            areaHint >= 0.15 -> 5
            areaHint >= 0.02 -> 6
            else -> 5
        }
    }

    private fun sampleSteps(span: Double, prefixLength: Int): Int {
        val cells = when (prefixLength) {
            3 -> 4
            4 -> 5
            5 -> 6
            6 -> 8
            else -> 10
        }
        val scaled = ceil(max(span, 0.001) * cells).toInt()
        return scaled.coerceIn(2, 12)
    }
}
