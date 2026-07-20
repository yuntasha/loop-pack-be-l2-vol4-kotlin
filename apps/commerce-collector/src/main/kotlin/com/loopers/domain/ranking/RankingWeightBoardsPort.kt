package com.loopers.domain.ranking

/**
 * 적재 대상 버전 목록(ranking:weights:boards KV) 조회 포트.
 * 어댑터가 캐시(수 초)와 폴백(KV 없음/장애 → 기본 v1 가중치)을 책임진다 —
 * 설정 조회 장애로 랭킹 적재가 멈추면 안 된다.
 */
interface RankingWeightBoardsPort {
    /** 적재해야 할 버전별 가중치 목록. 항상 1개 이상을 반환한다. */
    fun getActiveBoards(): List<RankingWeights>
}
