package com.devoceanblue.stayvista.domain.search

import com.devoceanblue.stayvista.common.cache.SimpleTtlCache
import com.devoceanblue.stayvista.domain.common.PlaceIdCodec
import com.devoceanblue.stayvista.domain.common.PlaceType
import com.devoceanblue.stayvista.domain.fx.FxService
import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.stereotype.Service

@Service
class PriceCalendarService(
    private val mapper: PriceCalendarMapper,
    private val cache: SimpleTtlCache,
    private val fxService: FxService,
    private val meterRegistry: MeterRegistry,
) {
    fun calendar(request: PriceCalendarRequest): PriceCalendarData {
        val startedAt = System.nanoTime()
        val normalized = request.normalize()
        val place = resolvePlace(normalized.place_id)
        val placeTypeTag = place.type.name.lowercase()
        val cacheKey = buildCacheKey(normalized)
        cache.get<PriceCalendarData>(cacheKey)?.let {
            meterRegistry.counter("price_calendar_requests_total", "cache", "hit", "place_type", placeTypeTag).increment()
            meterRegistry.timer("price_calendar_latency_ms", "cache", "hit", "place_type", placeTypeTag)
                .record(Duration.ofNanos(System.nanoTime() - startedAt))
            return it
        }
        meterRegistry.counter("price_calendar_requests_total", "cache", "miss", "place_type", placeTypeTag).increment()

        val baseDays = when (place.type) {
            PlaceType.PROPERTY -> loadPropertyCalendar(place.canonicalId, normalized.from, normalized.to)
            PlaceType.POI -> {
                val city = resolvePoiCity(place.canonicalId)
                if (city == null) {
                    emptyList()
                } else {
                    loadCityCalendar(city, normalized.from, normalized.to)
                }
            }

            PlaceType.CITY -> loadCityCalendar(place.canonicalId, normalized.from, normalized.to)
            PlaceType.STATION,
            PlaceType.AIRPORT,
            -> emptyList()
        }

        val converted = baseDays.map { day ->
            val convertedPrice = day.min_price_krw?.let {
                fxService.convert(it, "KRW", normalized.currency)
            }
            PriceCalendarDay(
                date = day.date,
                min_price = convertedPrice,
                currency = normalized.currency,
                available = convertedPrice != null,
            )
        }

        val response = PriceCalendarData(
            place_id = normalized.place_id,
            place_type = place.type.name.lowercase(),
            currency = normalized.currency,
            from = normalized.from.toString(),
            to = normalized.to.toString(),
            days = converted,
            meta = PriceCalendarMeta(
                nights = normalized.to.toEpochDay() - normalized.from.toEpochDay(),
                cache_ttl_seconds = 600,
            ),
        )
        cache.put(cacheKey, ttlMillis = 600_000, value = response)
        meterRegistry.timer("price_calendar_latency_ms", "cache", "miss", "place_type", placeTypeTag)
            .record(Duration.ofNanos(System.nanoTime() - startedAt))
        val availabilityRatio = if (converted.isEmpty()) 0.0 else converted.count { it.available }.toDouble() / converted.size.toDouble()
        meterRegistry.summary("price_calendar_available_days_ratio", "place_type", placeTypeTag).record(availabilityRatio)
        return response
    }

    private fun resolvePlace(placeId: String): ResolvedPlace {
        if (!placeId.contains(':')) {
            return ResolvedPlace(
                PlaceType.CITY,
                CityCanonicalizer.canonicalize(placeId) ?: placeId,
            )
        }
        val parsed = PlaceIdCodec.parseOrNull(placeId)
        if (parsed != null) {
            if (parsed.type == PlaceType.CITY) {
                return ResolvedPlace(
                    parsed.type,
                    CityCanonicalizer.canonicalize(parsed.canonicalId) ?: parsed.canonicalId,
                )
            }
            return ResolvedPlace(parsed.type, parsed.canonicalId)
        }
        return ResolvedPlace(
            PlaceType.CITY,
            CityCanonicalizer.canonicalize(placeId) ?: placeId,
        )
    }

    private fun resolvePoiCity(poiCanonicalId: String): String? {
        val poiId = poiCanonicalId.toLongOrNull() ?: return null
        return mapper.findPoiCity(poiId)?.let { CityCanonicalizer.canonicalize(it) ?: it }
    }

    private fun loadPropertyCalendar(propertyCanonicalId: String, from: LocalDate, to: LocalDate): List<CalendarPriceRow> {
        val propertyId = propertyCanonicalId.toLongOrNull() ?: return emptyList()
        val minPrice = mapper.findPropertyMinPrice(propertyId)

        return buildDateSeries(from, to).map { date ->
            CalendarPriceRow(
                date = date.toString(),
                min_price_krw = minPrice?.takeIf { it > 0 },
            )
        }
    }

    private fun loadCityCalendar(city: String, from: LocalDate, to: LocalDate): List<CalendarPriceRow> {
        val rows = mapper.listCityCalendar(
            city = city,
            from = java.sql.Date.valueOf(from),
            to = java.sql.Date.valueOf(to),
        )
        if (rows.isNotEmpty()) {
            return rows
        }

        val fallbackMin = mapper.findCityFallbackMinPrice(city)

        return buildDateSeries(from, to).map { date ->
            CalendarPriceRow(
                date = date.toString(),
                min_price_krw = fallbackMin?.takeIf { it > 0 },
            )
        }
    }

    private fun buildDateSeries(from: LocalDate, to: LocalDate): List<LocalDate> {
        val days = mutableListOf<LocalDate>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            days += cursor
            cursor = cursor.plusDays(1)
        }
        return days
    }

    private fun buildCacheKey(request: PriceCalendarRequest): String {
        val guestsHash = MessageDigest.getInstance("SHA-256")
            .digest("${request.rooms}|${request.adults}|${request.children}|${request.children_ages.joinToString(",")}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "pc:${request.place_id}:${request.from}:${request.to}:${request.currency}:$guestsHash"
    }

    private data class ResolvedPlace(
        val type: PlaceType,
        val canonicalId: String,
    )
}

data class PriceCalendarRequest(
    val place_id: String,
    val from: LocalDate,
    val to: LocalDate,
    val currency: String,
    val rooms: Int,
    val adults: Int,
    val children: Int,
    val children_ages: List<Int>,
) {
    fun normalize(): PriceCalendarRequest {
        val safeFrom = from
        val safeTo = if (to.isBefore(from)) from.plusDays(59) else to
        val boundedTo = if (safeTo.isAfter(safeFrom.plusDays(93))) safeFrom.plusDays(93) else safeTo
        return copy(
            from = safeFrom,
            to = boundedTo,
            currency = currency.trim().uppercase().ifBlank { "KRW" },
            rooms = rooms.coerceIn(1, 8),
            adults = adults.coerceIn(1, 16),
            children = children.coerceIn(0, 8),
            children_ages = children_ages.map { it.coerceIn(0, 17) }.take(children.coerceIn(0, 8)),
        )
    }
}

data class PriceCalendarData(
    val place_id: String,
    val place_type: String,
    val currency: String,
    val from: String,
    val to: String,
    val days: List<PriceCalendarDay>,
    val meta: PriceCalendarMeta,
)

data class PriceCalendarDay(
    val date: String,
    val min_price: Long?,
    val currency: String,
    val available: Boolean,
)

data class PriceCalendarMeta(
    val nights: Long,
    val cache_ttl_seconds: Int,
)

data class CalendarPriceRow(
    val date: String,
    val min_price_krw: Long?,
)

@Mapper
interface PriceCalendarMapper {
    @Select("SELECT city FROM poi WHERE id = #{poiId} LIMIT 1")
    fun findPoiCity(@Param("poiId") poiId: Long): String?

    @Select(
        """
        SELECT MIN(base_price)
        FROM room_type
        WHERE property_id = #{propertyId}
          AND status = 'ACTIVE'
        """,
    )
    fun findPropertyMinPrice(@Param("propertyId") propertyId: Long): Long?

    @Select(
        """
        SELECT DATE_FORMAT(stay_date, '%Y-%m-%d') AS date,
               min_price_krw
        FROM city_day_min_price
        WHERE city = #{city}
          AND stay_date >= #{from}
          AND stay_date <= #{to}
        ORDER BY stay_date ASC
        """,
    )
    fun listCityCalendar(
        @Param("city") city: String,
        @Param("from") from: java.sql.Date,
        @Param("to") to: java.sql.Date,
    ): List<CalendarPriceRow>

    @Select(
        """
        SELECT MIN(rt.base_price)
        FROM property p
        JOIN room_type rt ON rt.property_id = p.id AND rt.status = 'ACTIVE'
        WHERE p.city = #{city}
          AND p.status = 'ACTIVE'
        """,
    )
    fun findCityFallbackMinPrice(@Param("city") city: String): Long?
}
