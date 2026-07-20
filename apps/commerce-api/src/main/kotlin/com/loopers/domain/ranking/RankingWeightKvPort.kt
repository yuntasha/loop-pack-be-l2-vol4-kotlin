package com.loopers.domain.ranking

/**
 * 가중치 설정을 Redis KV로 전파하는 아웃바운드 포트. KV는 캐시일 뿐 진실 원천은 MySQL이며,
 * 재구성은 전체 설정을 읽어 덮어쓰므로 멱등하다.
 */
interface RankingWeightKvPort {
    /** ranking:weights:boards 갱신 — collector가 읽는 적재 대상 버전 목록(RETIRED 제외 전체). */
    fun syncBoards(configs: List<RankingWeightConfig>)

    /** ranking:weights:active 갱신 — 조회측이 서빙 키를 결정하는 활성 버전 포인터(flip). */
    fun setActive(version: String)
}
