package com.loopers.domain.ranking

interface RankingRepositoryPort {
    /** 점수 내림차순으로 offset부터 limit개 조회. 보드가 없으면 빈 리스트. */
    fun getPage(board: RankingBoard, offset: Long, limit: Long): List<RankingEntry>

    fun getTotalCount(board: RankingBoard): Long

    /** 특정 상품의 랭킹 항목. 보드에 없으면 null. */
    fun getEntry(board: RankingBoard, productId: Long): RankingEntry?
}
