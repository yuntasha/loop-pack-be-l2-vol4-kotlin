package com.loopers.interfaces.api.product

import com.loopers.domain.common.PageRequest
import com.loopers.domain.product.ProductSort
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
@RequestMapping("/api-admin/v1/products")
class ProductAdminController(
    private val productApplicationService: ProductAdminApplicationServicePort,
) {
    @GetMapping
    fun getProducts(
        @RequestParam(name = "brandId", required = false) brandId: Long?,
        @RequestParam(name = "sort", required = false) sort: String?,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ): ApiResponse<PageView<ProductAdminV1Dto.ProductSummaryResponse>> {
        val result = productApplicationService.getProducts(
            brandId,
            ProductSort.from(sort),
            PageRequest(page = page, size = size),
        )
        return ApiResponse.success(PageView.from(result, ProductAdminV1Dto.ProductSummaryResponse::from))
    }

    @GetMapping("/{id}")
    fun getProduct(
        @PathVariable id: Long,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse> {
        val detail = productApplicationService.getProduct(id)
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(detail))
    }

    @PostMapping
    fun createProduct(
        @RequestBody request: ProductAdminV1Dto.CreateProductRequest,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse> {
        val detail = productApplicationService.createProduct(request.toCommand())
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(detail))
    }

    @PutMapping("/{id}")
    fun updateProduct(
        @PathVariable id: Long,
        @RequestBody request: ProductAdminV1Dto.UpdateProductRequest,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse> {
        val detail = productApplicationService.updateProduct(request.toCommand(id))
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(detail))
    }

    @DeleteMapping("/{id}")
    fun deleteProduct(
        @PathVariable id: Long,
    ): ApiResponse<Any> {
        productApplicationService.deleteProduct(id)
        return ApiResponse.success()
    }
}
