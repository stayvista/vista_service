package com.devoceanblue.stayvista.domain.payment

import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PaymentGatewayTest {
    private val paymentGateway = PaymentGateway(SimpleMeterRegistry())

    @Test
    fun `authorize should succeed with normal token`() {
        assertDoesNotThrow {
            paymentGateway.authorize(
                PaymentAuthorizationRequest(
                    paymentMethod = "CARD",
                    paymentToken = "paytok_ok_123",
                    amount = 10000,
                    currency = "KRW",
                    referenceType = "BOOKING",
                    referenceId = "bkg_1",
                ),
            )
        }
    }

    @Test
    fun `authorize should fail with fail-prefixed token`() {
        val exception = assertThrows(DomainException::class.java) {
            paymentGateway.authorize(
                PaymentAuthorizationRequest(
                    paymentMethod = "CARD",
                    paymentToken = "fail_test",
                    amount = 10000,
                    currency = "KRW",
                    referenceType = "BOOKING",
                    referenceId = "bkg_1",
                ),
            )
        }
        assertEquals(ErrorCode.PAYMENT_AUTH_FAILED, exception.errorCode)
    }
}
