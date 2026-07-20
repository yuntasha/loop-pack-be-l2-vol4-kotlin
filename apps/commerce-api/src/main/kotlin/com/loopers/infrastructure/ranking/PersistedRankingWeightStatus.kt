package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.RankingWeightStatus

/** 영속 표현 — 도메인 enum이 레이어를 넘지 않도록 분리하고 경계에서 명시 변환한다. */
enum class PersistedRankingWeightStatus {
    PREPARING,
    ACTIVE,
    RETIRED,
    ;

    fun toDomain(): RankingWeightStatus = when (this) {
        PREPARING -> RankingWeightStatus.PREPARING
        ACTIVE -> RankingWeightStatus.ACTIVE
        RETIRED -> RankingWeightStatus.RETIRED
    }

    companion object {
        fun from(status: RankingWeightStatus): PersistedRankingWeightStatus = when (status) {
            RankingWeightStatus.PREPARING -> PREPARING
            RankingWeightStatus.ACTIVE -> ACTIVE
            RankingWeightStatus.RETIRED -> RETIRED
        }
    }
}
