package com.loopers.infrastructure.payment

import com.loopers.domain.payment.PaymentGatewayPort
import com.loopers.domain.payment.PaymentGatewayRequest
import com.loopers.domain.payment.PaymentStatus
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.ninjasquad.springmockk.MockkBean
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.SocketTimeoutException

/**
 * PG 어댑터의 회복 전략(재시도·서킷 브레이커·폴백)이 실제로 동작하는지 검증한다.
 * 외부 호출 경계인 [PgPaymentClient] 만 목으로 대체하고, 어댑터 + resilience4j AOP 는 실제로 로드한다.
 */
// 프로덕션 서킷 설정(sliding-window 100 / minimum-calls 50)으로는 테스트의 15회 실패(5회 × 재시도 3)로
// 서킷이 열리지 않는다. 상태 전이 로직 자체를 검증하는 것이 목적이므로 테스트 전용 윈도우로 축소한다.
@SpringBootTest(
    properties = [
        "resilience4j.circuitbreaker.instances.pgPayment.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.pgPayment.minimum-number-of-calls=10",
    ],
)
class PgPaymentGatewayAdapterResilienceIntegrationTest @Autowired constructor(
    private val paymentGatewayPort: PaymentGatewayPort,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
) {
    @MockkBean
    private lateinit var pgPaymentClient: PgPaymentClient

    private val request = PaymentGatewayRequest(
        userId = 1L,
        orderId = 1L,
        cardType = "SAMSUNG",
        cardNo = "1234-5678-9814-1451",
        amount = 5_000L,
    )

    @BeforeEach
    fun resetCircuit() {
        circuitBreakerRegistry.circuitBreaker("pgPayment").reset()
    }

    private fun successResponse() = PgApiResponse(
        meta = PgMeta(result = "SUCCESS", errorCode = null, message = null),
        data = PgTransactionResponse(transactionKey = "TX-123", status = "SUCCESS", reason = null),
    )

    @DisplayName("일시적 장애(타임아웃)가 발생해도 재시도하여 성공하면 정상 응답을 반환한다.")
    @Test
    fun retriesOnTransientFailure_thenSucceeds() {
        var calls = 0
        every { pgPaymentClient.requestPayment(any(), any()) } answers {
            calls++
            if (calls < 3) throw SocketTimeoutException("timeout")
            successResponse()
        }

        val response = paymentGatewayPort.requestPayment(request)

        assertThat(response.transactionKey).isEqualTo("TX-123")
        assertThat(response.status).isEqualTo(PaymentStatus.SUCCESS)
        verify(exactly = 3) { pgPaymentClient.requestPayment(any(), any()) }
    }

    @DisplayName("재시도를 모두 소진하면 폴백으로 SERVICE_UNAVAILABLE 예외를 던진다.")
    @Test
    fun fallsBack_whenRetriesExhausted() {
        every { pgPaymentClient.requestPayment(any(), any()) } throws SocketTimeoutException("timeout")

        val ex = assertThrows<CoreException> { paymentGatewayPort.requestPayment(request) }

        assertThat(ex.errorType).isEqualTo(ErrorType.SERVICE_UNAVAILABLE)
        verify(exactly = 3) { pgPaymentClient.requestPayment(any(), any()) }
    }

    @DisplayName("반복 실패로 서킷이 열리면 이후 호출은 PG를 호출하지 않고 즉시 폴백된다.")
    @Test
    fun opensCircuit_andShortCircuitsSubsequentCalls() {
        var clientCalls = 0
        every { pgPaymentClient.requestPayment(any(), any()) } answers {
            clientCalls++
            throw SocketTimeoutException("timeout")
        }

        // 서킷이 열릴 때까지 반복 호출한다 (각 호출은 폴백되어 예외를 던진다).
        repeat(5) { runCatching { paymentGatewayPort.requestPayment(request) } }

        val circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgPayment")
        assertThat(circuitBreaker.state).isEqualTo(CircuitBreaker.State.OPEN)

        // 서킷이 열린 뒤 호출은 PG 클라이언트를 건드리지 않고 즉시 폴백된다.
        val callsBeforeShortCircuit = clientCalls
        val ex = assertThrows<CoreException> { paymentGatewayPort.requestPayment(request) }

        assertThat(ex.errorType).isEqualTo(ErrorType.SERVICE_UNAVAILABLE)
        assertThat(clientCalls).isEqualTo(callsBeforeShortCircuit)
    }
}
