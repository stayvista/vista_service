package com.devoceanblue.stayvista.common.idempotency

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class RequestHashUtilTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `hash should be stable regardless of top-level key order`() {
        val payloadA = linkedMapOf(
            "user_id" to 1001,
            "scope" to "BOOKING_HOLD",
            "request" to mapOf("room_type_id" to 2001, "rooms" to 1),
        )
        val payloadB = linkedMapOf(
            "request" to mapOf("rooms" to 1, "room_type_id" to 2001),
            "scope" to "BOOKING_HOLD",
            "user_id" to 1001,
        )

        val hashA = RequestHashUtil.sha256Canonical(objectMapper, payloadA)
        val hashB = RequestHashUtil.sha256Canonical(objectMapper, payloadB)

        assertEquals(hashA, hashB)
    }

    @Test
    fun `hash should be stable regardless of nested key order`() {
        val payloadA = mapOf(
            "outer" to mapOf(
                "b" to mapOf("y" to 2, "x" to 1),
                "a" to listOf(
                    mapOf("k2" to "v2", "k1" to "v1"),
                ),
            ),
        )
        val payloadB = mapOf(
            "outer" to mapOf(
                "a" to listOf(
                    mapOf("k1" to "v1", "k2" to "v2"),
                ),
                "b" to mapOf("x" to 1, "y" to 2),
            ),
        )

        val hashA = RequestHashUtil.sha256Canonical(objectMapper, payloadA)
        val hashB = RequestHashUtil.sha256Canonical(objectMapper, payloadB)

        assertEquals(hashA, hashB)
    }

    @Test
    fun `hash should differ when payload value differs`() {
        val payloadA = mapOf("event_id" to 100, "quantity" to 1)
        val payloadB = mapOf("event_id" to 100, "quantity" to 2)

        val hashA = RequestHashUtil.sha256Canonical(objectMapper, payloadA)
        val hashB = RequestHashUtil.sha256Canonical(objectMapper, payloadB)

        assertNotEquals(hashA, hashB)
    }
}
