package com.loopers.interfaces.api.brand

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
@RequestMapping("/api-admin/v1/brands")
class BrandAdminController(
    private val brandApplicationService: BrandAdminApplicationServicePort,
) {
    @GetMapping
    fun getBrands(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ): ApiResponse<PageView<BrandAdminV1Dto.BrandResponse>> {
        val result = brandApplicationService.getBrands(PageRequest(page = page, size = size))
        return ApiResponse.success(PageView.from(result, BrandAdminV1Dto.BrandResponse::from))
    }

    @GetMapping("/{id}")
    fun getBrand(
        @PathVariable id: Long,
    ): ApiResponse<BrandAdminV1Dto.BrandResponse> {
        val brand = brandApplicationService.getBrand(id)
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(brand))
    }

    @PostMapping
    fun createBrand(
        @RequestBody request: BrandAdminV1Dto.CreateBrandRequest,
    ): ApiResponse<BrandAdminV1Dto.BrandResponse> {
        val brand = brandApplicationService.createBrand(request.toCommand())
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(brand))
    }

    @PutMapping("/{id}")
    fun updateBrand(
        @PathVariable id: Long,
        @RequestBody request: BrandAdminV1Dto.UpdateBrandRequest,
    ): ApiResponse<BrandAdminV1Dto.BrandResponse> {
        val brand = brandApplicationService.updateBrand(request.toCommand(id))
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(brand))
    }

    @DeleteMapping("/{id}")
    fun deleteBrand(
        @PathVariable id: Long,
    ): ApiResponse<Any> {
        brandApplicationService.deleteBrand(id)
        return ApiResponse.success()
    }
}
