package com.loopers.application.ranking

import java.time.LocalDate

data class RankingPageResult(
    val date: LocalDate,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val items: List<RankingItemResult>,
)

data class RankingItemResult(
    val rank: Long,
    val productId: Long,
    val score: Double,
    /** 상품이 조회되지 않으면(삭제 등) null. */
    val productName: String?,
    val price: Long?,
)
