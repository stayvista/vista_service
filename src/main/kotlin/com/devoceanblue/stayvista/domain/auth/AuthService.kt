package com.devoceanblue.stayvista.domain.auth

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.sql.PreparedStatement
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val jdbcTemplate: JdbcTemplate,
    private val passwordHasher: PasswordHasher,
    private val redisSessionService: RedisSessionService,
) {
    fun register(request: RegisterRequest): AuthTokenData {
        val email = normalizeEmail(request.email)
        val name = request.name?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: email.substringBefore("@")
        val phone = request.phone?.trim()?.takeIf { it.isNotBlank() }
        val passwordHash = passwordHasher.hash(request.password)

        val keyHolder = GeneratedKeyHolder()
        try {
            jdbcTemplate.update({ connection ->
                val ps = connection.prepareStatement(
                    """
                    INSERT INTO user_account(email, password_hash, phone, name, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    PreparedStatement.RETURN_GENERATED_KEYS,
                )
                ps.setString(1, email)
                ps.setString(2, passwordHash)
                ps.setString(3, phone)
                ps.setString(4, name)
                ps
            }, keyHolder)
        } catch (e: DuplicateKeyException) {
            throw DomainException(ErrorCode.CONFLICT, "Email already in use")
        }
        val userId = keyHolder.key?.toLong() ?: throw DomainException(ErrorCode.INTERNAL, "Failed to create user")
        return issueToken(
            UserRow(
                id = userId,
                email = email,
                name = name,
                passwordHash = passwordHash,
                status = "ACTIVE",
            ),
        )
    }

    fun login(request: LoginRequest): AuthTokenData {
        val email = normalizeEmail(request.email)
        val user = jdbcTemplate.query(
            """
            SELECT id, email, name, password_hash, status
            FROM user_account
            WHERE email = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                UserRow(
                    id = rs.getLong("id"),
                    email = rs.getString("email"),
                    name = rs.getString("name") ?: rs.getString("email").substringBefore("@"),
                    passwordHash = rs.getString("password_hash"),
                    status = rs.getString("status"),
                )
            },
            email,
        ).firstOrNull() ?: throw DomainException(ErrorCode.UNAUTHORIZED, "Invalid email or password")

        if (user.status != "ACTIVE") {
            throw DomainException(ErrorCode.UNAUTHORIZED, "Invalid email or password")
        }
        if (!passwordHasher.matches(request.password, user.passwordHash)) {
            throw DomainException(ErrorCode.UNAUTHORIZED, "Invalid email or password")
        }
        return issueToken(user)
    }

    fun logout(authorization: String?): LogoutData {
        val token = redisSessionService.extractBearerToken(authorization)
        redisSessionService.invalidate(token)
        return LogoutData(
            logged_out = true,
        )
    }

    private fun issueToken(user: UserRow): AuthTokenData {
        val token = redisSessionService.createSession(user.id, user.email, user.name)
        return AuthTokenData(
            token_type = "Bearer",
            access_token = token.accessToken,
            expires_in_seconds = token.expiresInSeconds,
            user = AuthUserData(
                user_id = user.id,
                email = user.email,
                name = user.name,
            ),
        )
    }

    private fun normalizeEmail(raw: String): String {
        return raw.trim().lowercase()
    }
}

data class LoginRequest(
    @field:NotBlank
    @field:Email
    val email: String,
    @field:NotBlank
    @field:Size(min = 8, max = 128)
    val password: String,
)

data class RegisterRequest(
    @field:NotBlank
    @field:Email
    val email: String,
    @field:NotBlank
    @field:Size(min = 8, max = 128)
    val password: String,
    @field:Size(max = 100)
    val name: String? = null,
    @field:Size(max = 50)
    val phone: String? = null,
)

data class AuthTokenData(
    val token_type: String,
    val access_token: String,
    val expires_in_seconds: Long,
    val user: AuthUserData,
)

data class AuthUserData(
    val user_id: Long,
    val email: String,
    val name: String,
)

data class LogoutData(
    val logged_out: Boolean,
)

private data class UserRow(
    val id: Long,
    val email: String,
    val name: String,
    val passwordHash: String?,
    val status: String,
)
