package com.devoceanblue.stayvista.domain.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class RedisSessionService(
    private val redisTemplate: StringRedisTemplate,
    private val clock: Clock,
    @Value("\${stayvista.auth.session-ttl-seconds:7200}") private val sessionTtlSeconds: Long,
) {
    fun createSession(userId: Long, email: String, name: String): IssuedSessionToken {
        val now = Instant.now(clock)
        val expiresAt = now.plusSeconds(sessionTtlSeconds)
        val token = "svs_${UUID.randomUUID().toString().replace("-", "")}"
        val key = sessionKey(token)
        redisTemplate.opsForHash<String, String>().putAll(
            key,
            mapOf(
                "user_id" to userId.toString(),
                "email" to email,
                "name" to name,
                "issued_at" to now.epochSecond.toString(),
                "expires_at" to expiresAt.epochSecond.toString(),
            ),
        )
        redisTemplate.expire(key, Duration.ofSeconds(sessionTtlSeconds))
        return IssuedSessionToken(
            accessToken = token,
            expiresInSeconds = sessionTtlSeconds,
            expiresAtEpochSeconds = expiresAt.epochSecond,
        )
    }

    fun resolvePrincipal(rawToken: String?): AuthPrincipal? {
        if (rawToken.isNullOrBlank()) return null
        if (!rawToken.startsWith("svs_")) return null
        if (rawToken.length < 12) return null

        val payload = redisTemplate.opsForHash<String, String>().entries(sessionKey(rawToken))
        if (payload.isEmpty()) return null

        val userId = payload["user_id"]?.toLongOrNull() ?: return null
        val email = payload["email"] ?: return null
        val name = payload["name"] ?: return null
        val expiresAt = payload["expires_at"]?.toLongOrNull() ?: return null
        if (expiresAt <= Instant.now(clock).epochSecond) {
            invalidate(rawToken)
            return null
        }

        redisTemplate.expire(sessionKey(rawToken), Duration.ofSeconds(sessionTtlSeconds))
        return AuthPrincipal(
            userId = userId,
            email = email,
            name = name,
            expiresAtEpochSeconds = expiresAt,
        )
    }

    fun invalidate(rawToken: String?) {
        if (rawToken.isNullOrBlank()) return
        if (!rawToken.startsWith("svs_")) return
        redisTemplate.delete(sessionKey(rawToken))
    }

    fun extractBearerToken(authorization: String?): String? {
        if (authorization.isNullOrBlank()) return null
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) return null
        return authorization.substringAfter("Bearer ", "").trim().takeIf { it.isNotBlank() }
    }

    private fun sessionKey(token: String): String = "auth:session:$token"
}

data class IssuedSessionToken(
    val accessToken: String,
    val expiresInSeconds: Long,
    val expiresAtEpochSeconds: Long,
)

