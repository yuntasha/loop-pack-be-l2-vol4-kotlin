package com.loopers.domain.ranking

/**
 * 이월(carry-over) 상태 머신. 랭킹 키 존재 여부가 아니라 이 상태 값이 이월 완료의 유일한 신호다 —
 * 실시간 이벤트가 오늘 키를 먼저 생성해도 이월 누락을 정확히 감지할 수 있다.
 */
enum class RankingRolloverStatus {
    /** 이월 완료. 오늘 보드를 정상 조회한다. */
    DONE,

    /** 배치/복구가 실행 중. 자정 이후 관측 자체가 "아직 안 끝났다"는 뜻 — 폴백 + WARN 대상. */
    IN_PROGRESS,

    /** 이월 시작조차 안 됨(배치 실패). 선점 성공 시 복구를 트리거한다. */
    NOT_STARTED,
}
