package com.devoceanblue.stayvista.common.api

import com.devoceanblue.stayvista.common.web.RequestIdContext

data class ApiEnvelope<T>(
    val request_id: String,
    val data: T,
)

data class ApiErrorEnvelope(
    val request_id: String,
    val error: ApiErrorBody,
)

data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)

object ApiResponses {
    fun <T> ok(data: T): ApiEnvelope<T> = ApiEnvelope(
        request_id = RequestIdContext.current(),
        data = data,
    )

    fun error(error: ApiErrorBody): ApiErrorEnvelope = ApiErrorEnvelope(
        request_id = RequestIdContext.current(),
        error = error,
    )
}
