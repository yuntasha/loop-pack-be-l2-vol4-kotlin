package com.loopers.interfaces.api.order

import com.loopers.domain.common.PageRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.auth.AdminAuth
import com.loopers.interfaces.api.common.PageView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@AdminAuth
@RequestMapping("/api-admin/v1/orders")
class OrderAdminController(
    private val orderAdminApplicationService: OrderAdminApplicationServicePort,
) {
    @GetMapping
    fun getOrders(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ): ApiResponse<PageView<OrderAdminV1Dto.AdminOrderSummaryResponse>> {
        val result = orderAdminApplicationService.getOrders(PageRequest(page = page, size = size))
        return ApiResponse.success(PageView.from(result, OrderAdminV1Dto.AdminOrderSummaryResponse::from))
    }

    @GetMapping("/{id}")
    fun getOrder(
        @PathVariable id: Long,
    ): ApiResponse<OrderAdminV1Dto.AdminOrderDetailResponse> {
        val detail = orderAdminApplicationService.getOrder(id)
        return ApiResponse.success(OrderAdminV1Dto.AdminOrderDetailResponse.from(detail))
    }
}
