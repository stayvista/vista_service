package com.devoceanblue.stayvista.domain.auth

data class AuthPrincipal(
    val userId: Long,
    val email: String,
    val name: String,
    val expiresAtEpochSeconds: Long,
)

