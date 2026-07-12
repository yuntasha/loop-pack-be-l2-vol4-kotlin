package com.loopers.interfaces.api.coupon

import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.auth.UserAuth
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/coupons")
class CouponController(
    private val couponApplicationService: CouponApplicationServicePort,
) {
    @PostMapping("/{couponId}/issue")
    fun issueCoupon(
        @UserAuth userId: Long,
        @PathVariable couponId: Long,
    ): ApiResponse<CouponV1Dto.IssueRequestResponse> {
        val result = couponApplicationService.issueCoupon(userId, couponId)
        return ApiResponse.success(CouponV1Dto.IssueRequestResponse.from(result))
    }

    @GetMapping("/{couponId}/issue/status")
    fun getIssueStatus(
        @UserAuth userId: Long,
        @PathVariable couponId: Long,
    ): ApiResponse<CouponV1Dto.IssueStatusResponse> {
        val result = couponApplicationService.getIssueStatus(userId, couponId)
        return ApiResponse.success(CouponV1Dto.IssueStatusResponse.from(result))
    }
}
