package com.loopers.domain.ranking

import java.time.LocalDate

/** 랭킹 보드의 한 항목. rank는 1부터 시작한다. */
data class RankingEntry(
    val productId: Long,
    val score: Double,
    val rank: Long,
)

/** 랭킹 페이지 조회 결과 (도메인 모델). 상품 정보 hydration 전 상태다. */
data class RankingPage(
    val date: LocalDate,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val entries: List<RankingEntry>,
)
