package com.devoceanblue.stayvista.domain.search

import java.util.Locale

object CityCanonicalizer {
    private val aliasMap: Map<String, String> = mapOf(
        "seoul" to "Seoul",
        "서울" to "Seoul",
        "서울시" to "Seoul",
        "서울특별시" to "Seoul",
        "busan" to "Busan",
        "pusan" to "Busan",
        "부산" to "Busan",
        "부산시" to "Busan",
        "부산광역시" to "Busan",
        "jeju" to "Jeju",
        "제주" to "Jeju",
        "제주시" to "Jeju",
        "제주도" to "Jeju",
        "incheon" to "Incheon",
        "인천" to "Incheon",
        "인천시" to "Incheon",
        "인천광역시" to "Incheon",
        "daegu" to "Daegu",
        "대구" to "Daegu",
        "daejeon" to "Daejeon",
        "대전" to "Daejeon",
        "gwangju" to "Gwangju",
        "광주" to "Gwangju",
        "ulsan" to "Ulsan",
        "울산" to "Ulsan",
        "suwon" to "Suwon",
        "수원" to "Suwon",
        "gangneung" to "Gangneung",
        "강릉" to "Gangneung",
    )

    fun canonicalize(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalizedKey = trimmed.lowercase(Locale.ROOT)
        aliasMap[normalizedKey]?.let { return it }

        if (trimmed.all { it.isLetter() || it.isWhitespace() || it == '-' }) {
            return trimmed.split(Regex("\\s+"))
                .joinToString(" ") { part ->
                    if (part.isBlank()) {
                        part
                    } else {
                        part.substring(0, 1).uppercase(Locale.ROOT) + part.substring(1).lowercase(Locale.ROOT)
                    }
                }
        }

        return trimmed
    }
}
