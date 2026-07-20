package com.loopers.interfaces.api.product

import com.loopers.domain.product.ProductDetail
import com.loopers.domain.product.ProductSummary

class ProductV1Dto {
    data class ProductResponse(
        val id: Long,
        val name: String,
        val price: Long,
        val description: String,
        val brandId: Long,
        val brandName: String,
        val stockQuantity: Int,
        val likeCount: Long,
        val ranking: RankingResponse?,
    ) {
        companion object {
            fun from(detail: ProductDetail): ProductResponse = ProductResponse(
                id = detail.id,
                name = detail.name,
                price = detail.price,
                description = detail.description,
                brandId = detail.brandId,
                brandName = detail.brandName,
                stockQuantity = detail.stockQuantity,
                likeCount = detail.likeCount,
                ranking = detail.ranking?.let { RankingResponse(rank = it.rank, score = it.score) },
            )
        }
    }

    data class RankingResponse(
        val rank: Long,
        val score: Double,
    )

    data class ProductSummaryResponse(
        val id: Long,
        val name: String,
        val price: Long,
        val brandId: Long,
        val brandName: String,
        val stockQuantity: Int,
        val likeCount: Long,
    ) {
        companion object {
            fun from(summary: ProductSummary): ProductSummaryResponse = ProductSummaryResponse(
                id = summary.id,
                name = summary.name,
                price = summary.price,
                brandId = summary.brandId,
                brandName = summary.brandName,
                stockQuantity = summary.stockQuantity,
                likeCount = summary.likeCount,
            )
        }
    }
}
