package com.devoceanblue.stayvista.domain.auth

import com.devoceanblue.stayvista.common.api.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/auth")
class AuthController(
    private val authService: AuthService,
) {
    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest) = ApiResponses.ok(
        authService.register(request),
    )

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest) = ApiResponses.ok(
        authService.login(request),
    )

    @PostMapping("/logout")
    fun logout(
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
    ) = ApiResponses.ok(
        authService.logout(authorization),
    )
}
