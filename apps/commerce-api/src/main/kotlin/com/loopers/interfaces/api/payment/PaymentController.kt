package com.loopers.interfaces.api.payment

import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.auth.UserAuth
import com.loopers.interfaces.api.waitingqueue.WaitingQueue
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val paymentApplicationService: PaymentApplicationServicePort,
) {
    @PostMapping
    @WaitingQueue("order")
    fun pay(
        @UserAuth userId: Long,
        @RequestBody request: PaymentV1Dto.PaymentRequest,
    ): ApiResponse<PaymentV1Dto.PaymentResponse> {
        val result = paymentApplicationService.pay(request.toCommand(userId))
        return ApiResponse.success(PaymentV1Dto.PaymentResponse.from(result))
    }

    @PostMapping("/callback")
    fun handleCallback(
        @RequestBody request: PaymentV1Dto.PaymentCallbackRequest,
    ): ApiResponse<Any> {
        paymentApplicationService.handleCallback(request.toCommand())
        return ApiResponse.success()
    }

    /**
     * 콜백 미수신/미확정 결제건의 상태를 PG 에 직접 조회해 복구한다(수동 트리거).
     */
    @PostMapping("/{transactionKey}/reconcile")
    fun reconcile(
        @PathVariable("transactionKey") transactionKey: String,
    ): ApiResponse<PaymentV1Dto.PaymentResponse> {
        val result = paymentApplicationService.reconcile(transactionKey)
        return ApiResponse.success(PaymentV1Dto.PaymentResponse.from(result))
    }
}
