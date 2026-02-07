package com.devoceanblue.stayvista.domain.payment

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

@Service
class PaymentGateway(
    private val meterRegistry: MeterRegistry,
) {
    fun authorize(request: PaymentAuthorizationRequest) {
        // Stub behavior: tokens with fail/error prefix are rejected.
        if (request.paymentToken.startsWith("fail", ignoreCase = true) ||
            request.paymentToken.startsWith("error", ignoreCase = true)
        ) {
            meterRegistry.counter("payment_authorize_total", "result", "FAILED").increment()
            throw DomainException(
                ErrorCode.PAYMENT_AUTH_FAILED,
                "Payment authorization failed",
                details = mapOf(
                    "payment_method" to request.paymentMethod,
                    "reason" to "stub_rejection",
                ),
            )
        }
        meterRegistry.counter("payment_authorize_total", "result", "SUCCESS").increment()
    }
}

data class PaymentAuthorizationRequest(
    val paymentMethod: String,
    val paymentToken: String,
    val amount: Long,
    val currency: String,
    val referenceType: String,
    val referenceId: String,
)
