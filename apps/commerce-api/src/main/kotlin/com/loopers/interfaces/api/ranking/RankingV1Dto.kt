package com.loopers.interfaces.api.ranking

import com.loopers.application.ranking.RankingItemResult
import com.loopers.application.ranking.RankingPageResult
import java.time.format.DateTimeFormatter

class RankingV1Dto {
    data class RankingPageResponse(
        val date: String,
        val page: Int,
        val size: Int,
        val totalCount: Long,
        val items: List<RankingItemResponse>,
    ) {
        companion object {
            fun from(result: RankingPageResult): RankingPageResponse = RankingPageResponse(
                date = result.date.format(DateTimeFormatter.BASIC_ISO_DATE),
                page = result.page,
                size = result.size,
                totalCount = result.totalCount,
                items = result.items.map(RankingItemResponse::from),
            )
        }
    }

    data class RankingItemResponse(
        val rank: Long,
        val productId: Long,
        val score: Double,
        val productName: String?,
        val price: Long?,
    ) {
        companion object {
            fun from(item: RankingItemResult): RankingItemResponse = RankingItemResponse(
                rank = item.rank,
                productId = item.productId,
                score = item.score,
                productName = item.productName,
                price = item.price,
            )
        }
    }
}
