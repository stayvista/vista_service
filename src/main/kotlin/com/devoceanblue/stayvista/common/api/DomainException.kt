package com.devoceanblue.stayvista.common.api

class DomainException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.code,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)
