package com.devoceanblue.stayvista.common.time

import java.time.LocalDate

object DateRange {
    fun nights(checkIn: LocalDate, checkOut: LocalDate): List<LocalDate> {
        if (!checkOut.isAfter(checkIn)) {
            return emptyList()
        }
        val dates = mutableListOf<LocalDate>()
        var cursor = checkIn
        while (cursor.isBefore(checkOut)) {
            dates.add(cursor)
            cursor = cursor.plusDays(1)
        }
        return dates
    }
}
