package com.devoceanblue.stayvista.common.idempotency

import java.security.MessageDigest
import tools.jackson.databind.ObjectMapper

object RequestHashUtil {
    fun sha256Canonical(objectMapper: ObjectMapper, payload: Any): String {
        val serialized = objectMapper.writeValueAsString(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
