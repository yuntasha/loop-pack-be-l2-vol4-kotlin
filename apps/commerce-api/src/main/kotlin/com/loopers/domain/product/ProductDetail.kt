package com.loopers.domain.product

import com.loopers.domain.brand.Brand
import com.loopers.domain.ranking.RankingEntry
import com.loopers.domain.stock.Stock

data class ProductDetail(
    val id: Long,
    val name: String,
    val price: Long,
    val description: String,
    val brandId: Long,
    val brandName: String,
    val stockQuantity: Int,
    val likeCount: Long,
    /** 오늘 랭킹 보드에 집계가 없으면 null. */
    val ranking: RankingEntry? = null,
) {
    companion object {
        fun of(product: Product, brand: Brand, stock: Stock, likeCount: Long, ranking: RankingEntry? = null): ProductDetail =
            ProductDetail(
                id = product.id,
                name = product.name,
                price = product.price,
                description = product.description,
                brandId = product.brandId,
                brandName = brand.name,
                stockQuantity = stock.quantity,
                likeCount = likeCount,
                ranking = ranking,
            )
    }
}

data class ProductSummary(
    val id: Long,
    val name: String,
    val price: Long,
    val brandId: Long,
    val brandName: String,
    val stockQuantity: Int,
    val likeCount: Long,
) {
    companion object {
        fun of(product: Product, brand: Brand, stock: Stock, likeCount: Long): ProductSummary = ProductSummary(
            id = product.id,
            name = product.name,
            price = product.price,
            brandId = product.brandId,
            brandName = brand.name,
            stockQuantity = stock.quantity,
            likeCount = likeCount,
        )
    }
}
