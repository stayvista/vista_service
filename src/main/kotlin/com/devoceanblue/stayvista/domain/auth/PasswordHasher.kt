package com.devoceanblue.stayvista.domain.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class PasswordHasher(
    @Value("\${stayvista.auth.password.pbkdf2-iterations:180000}") private val iterations: Int,
    @Value("\${stayvista.auth.password.pbkdf2-key-length:256}") private val keyLength: Int,
) {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun hash(rawPassword: String): String {
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return encode(rawPassword, salt, iterations, keyLength)
    }

    fun matches(rawPassword: String, encodedPassword: String?): Boolean {
        if (encodedPassword.isNullOrBlank()) return false
        val chunks = encodedPassword.split("$")
        if (chunks.size != 4 || chunks[0] != "pbkdf2") return false
        val parsedIterations = chunks[1].toIntOrNull() ?: return false
        val salt = runCatching { decoder.decode(chunks[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(chunks[3]) }.getOrNull() ?: return false
        val actual = derive(rawPassword, salt, parsedIterations, expected.size * 8)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun encode(rawPassword: String, salt: ByteArray, rounds: Int, bits: Int): String {
        val hash = derive(rawPassword, salt, rounds, bits)
        return "pbkdf2$$rounds$${encoder.encodeToString(salt)}$${encoder.encodeToString(hash)}"
    }

    private fun derive(rawPassword: String, salt: ByteArray, rounds: Int, bits: Int): ByteArray {
        val spec = PBEKeySpec(rawPassword.toCharArray(), salt, rounds, bits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }
}

