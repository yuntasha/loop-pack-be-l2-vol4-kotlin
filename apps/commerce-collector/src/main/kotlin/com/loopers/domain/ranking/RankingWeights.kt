package com.loopers.domain.ranking

/**
 * 버전별 랭킹 가중치. 값은 논리 가중치에 ×10한 저장 스케일 —
 * 이월(carry-over) 계수 0.1을 곱해도 점수가 정수를 유지해야 한다.
 */
data class RankingWeights(
    val version: String,
    private val weights: Map<RankingEventType, Long>,
) {
    init {
        require(version.isNotBlank()) { "version은 비어 있을 수 없다." }
        require(weights.values.all { it > 0 }) { "가중치는 양수여야 한다. version=$version, weights=$weights" }
    }

    /** 설정에 없는 타입은 기본 가중치로 계산한다 — 신규 타입 추가와 설정 반영 사이의 공백을 메운다. */
    fun weightOf(type: RankingEventType): Long = weights[type] ?: DEFAULT_WEIGHTS.getValue(type)

    companion object {
        const val DEFAULT_VERSION = "v1"
        val DEFAULT_WEIGHTS = mapOf(
            RankingEventType.VIEW to 10L,
            RankingEventType.LIKE to 50L,
            RankingEventType.ORDER to 500L,
        )

        fun default(): RankingWeights = RankingWeights(DEFAULT_VERSION, DEFAULT_WEIGHTS)
    }
}
