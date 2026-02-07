package com.devoceanblue.stayvista.common.idempotency

import java.security.MessageDigest
import tools.jackson.databind.ObjectMapper

object RequestHashUtil {
    fun sha256Canonical(objectMapper: ObjectMapper, payload: Any): String {
        val normalized = objectMapper.readValue(objectMapper.writeValueAsString(payload), Any::class.java)
        val serialized = canonicalJson(objectMapper, normalized)
        val digest = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalJson(objectMapper: ObjectMapper, value: Any?): String {
        if (value == null) return "null"
        if (value is Map<*, *>) {
            return value.entries
                .sortedBy { it.key?.toString() ?: "" }
                .joinToString(
                    prefix = "{",
                    postfix = "}",
                    separator = ",",
                ) { entry ->
                    val key = entry.key?.toString() ?: ""
                    "${objectMapper.writeValueAsString(key)}:${canonicalJson(objectMapper, entry.value)}"
                }
        }
        if (value is List<*>) {
            return value.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ",",
            ) { item ->
                canonicalJson(objectMapper, item)
            }
        }
        return objectMapper.writeValueAsString(value)
    }
}
