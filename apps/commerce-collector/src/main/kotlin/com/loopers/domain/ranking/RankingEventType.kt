package com.loopers.domain.ranking

/**
 * 랭킹 반영 이벤트 타입. 가중치는 상수가 아니라 버전별 설정([RankingWeights])에서 얻는다 —
 * 운영 중 가중치를 코드 배포 없이 바꾸기 위함이다.
 */
enum class RankingEventType {
    VIEW,
    LIKE,
    ORDER,
}
