package com.loopers.interfaces.api.coupon

import com.loopers.domain.common.PageRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.auth.AdminAuth
import com.loopers.interfaces.api.common.PageView
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@AdminAuth
@RequestMapping("/api-admin/v1/coupons")
class CouponAdminController(
    private val couponApplicationService: CouponAdminApplicationServicePort,
) {
    @GetMapping
    fun getCoupons(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ): ApiResponse<PageView<CouponAdminV1Dto.CouponResponse>> {
        val result = couponApplicationService.getCoupons(PageRequest(page = page, size = size))
        return ApiResponse.success(PageView.from(result, CouponAdminV1Dto.CouponResponse::from))
    }

    @GetMapping("/{id}")
    fun getCoupon(
        @PathVariable id: Long,
    ): ApiResponse<CouponAdminV1Dto.CouponResponse> {
        val result = couponApplicationService.getCoupon(id)
        return ApiResponse.success(CouponAdminV1Dto.CouponResponse.from(result))
    }

    @GetMapping("/{couponId}/issues")
    fun getCouponIssues(
        @PathVariable couponId: Long,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ): ApiResponse<PageView<CouponAdminV1Dto.IssueResponse>> {
        val result = couponApplicationService.getCouponIssues(couponId, PageRequest(page = page, size = size))
        return ApiResponse.success(PageView.from(result, CouponAdminV1Dto.IssueResponse::from))
    }

    @PostMapping
    fun createCoupon(
        @RequestBody request: CouponAdminV1Dto.CreateCouponRequest,
    ): ApiResponse<CouponAdminV1Dto.CouponResponse> {
        val result = couponApplicationService.createCoupon(request.toCommand())
        return ApiResponse.success(CouponAdminV1Dto.CouponResponse.from(result))
    }

    @PutMapping("/{id}")
    fun updateCoupon(
        @PathVariable id: Long,
        @RequestBody request: CouponAdminV1Dto.UpdateCouponRequest,
    ): ApiResponse<CouponAdminV1Dto.CouponResponse> {
        val result = couponApplicationService.updateCoupon(request.toCommand(id))
        return ApiResponse.success(CouponAdminV1Dto.CouponResponse.from(result))
    }

    @DeleteMapping("/{id}")
    fun deleteCoupon(
        @PathVariable id: Long,
    ): ApiResponse<Any> {
        couponApplicationService.deleteCoupon(id)
        return ApiResponse.success()
    }
}
