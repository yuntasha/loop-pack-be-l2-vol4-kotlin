package com.loopers.domain.ranking

import java.time.LocalDate

/**
 * 랭킹 조회 Domain Service. 활성 가중치 버전의 보드를 서빙한다.
 * Redis ZSET이 아는 것은 productId/score뿐이며, 상품 정보 병합(hydration)은 application 계층의 책임이다 —
 * 이 서비스는 Product를 모른다.
 */
class RankingService(
    private val rankingRepositoryPort: RankingRepositoryPort,
    private val rankingWeightViewPort: RankingWeightViewPort,
) {
    fun getPage(version: String, date: LocalDate, page: Int, size: Int): RankingPage =
        pageOf(RankingBoard.allOf(version, date), date, page, size)

    /**
     * 이월 미완료 폴백 조회(전날 보드). flip 당일엔 활성 버전의 전날 보드가 없을 수 있으므로(replay는 오늘만 구축)
     * 비어 있으면 boards KV의 다른 버전 보드로 대체한다 — 직전까지 서빙하던 구 버전은 은퇴 전이라 존재한다.
     */
    fun getFallbackPage(version: String, date: LocalDate, page: Int, size: Int): RankingPage =
        pageOf(RankingBoard.allOf(resolveServableVersion(version, date), date), date, page, size)

    fun getProductRanking(date: LocalDate, productId: Long): RankingEntry? =
        rankingRepositoryPort.getEntry(RankingBoard.allOf(rankingWeightViewPort.getActiveVersion(), date), productId)

    private fun resolveServableVersion(preferredVersion: String, date: LocalDate): String {
        if (rankingRepositoryPort.getTotalCount(RankingBoard.allOf(preferredVersion, date)) > 0) return preferredVersion
        return rankingWeightViewPort.getBoardVersions()
            .firstOrNull { it != preferredVersion && rankingRepositoryPort.getTotalCount(RankingBoard.allOf(it, date)) > 0 }
            ?: preferredVersion
    }

    private fun pageOf(board: RankingBoard, date: LocalDate, page: Int, size: Int): RankingPage {
        val offset = (page - 1L) * size
        val entries = rankingRepositoryPort.getPage(board, offset, size.toLong())
        val totalCount = rankingRepositoryPort.getTotalCount(board)
        return RankingPage(date = date, page = page, size = size, totalCount = totalCount, entries = entries)
    }
}
