package com.devoceanblue.stayvista.domain.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasswordHasherTest {
    @Test
    fun `should match seeded demo password hash`() {
        val hasher = PasswordHasher(
            iterations = 180000,
            keyLength = 256,
        )
        val seededHash = "pbkdf2\$180000\$zrJdcwlhFuelrP8QuYQejQ\$dcOZlouZNKPJl9xZxlfpxMCKBswsmTaimKRXa0fOU4o"

        assertTrue(hasher.matches("demo1234!", seededHash))
        assertFalse(hasher.matches("wrong-password", seededHash))
    }
}

