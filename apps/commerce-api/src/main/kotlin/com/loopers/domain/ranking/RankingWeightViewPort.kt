package com.loopers.domain.ranking

/**
 * 조회 경로에서 가중치 버전 정보를 읽는 포트. 어댑터가 폴백을 책임진다 —
 * KV 미존재/장애 시 v1 간주. 조회가 설정 장애로 실패하면 안 된다.
 */
interface RankingWeightViewPort {
    /** 서빙할 활성 버전 (ranking:weights:active). 미존재 시 v1. */
    fun getActiveVersion(): String

    /** 적재 중인 버전 목록 (ranking:weights:boards). flip 직후 폴백 후보 탐색용. 미존재 시 빈 목록. */
    fun getBoardVersions(): List<String>
}
