package com.loopers.domain.ranking

/**
 * 가중치 설정 상태 머신. PREPARING → ACTIVE → (강등: PREPARING) → RETIRED.
 * boards KV(적재 대상)에는 RETIRED가 아닌 모든 버전이, active KV(서빙 대상)에는 ACTIVE 버전 하나만 실린다 —
 * 전환 기간엔 적재 대상과 서빙 대상이 다르다(replay 중엔 이중 적재·v1 서빙, flip 후엔 v2 서빙·v1 병행 적재).
 */
enum class RankingWeightStatus {
    /** 등록됨. collector 이중 적재 + replay 진행 중(또는 flip 후 롤백 대비 병행 적재 중). */
    PREPARING,

    /** 서빙 중. 시스템에 하나만 존재한다. */
    ACTIVE,

    /** 은퇴. 적재 중단, 보드 키는 TTL 자연 소멸 대기. */
    RETIRED,
}
