package com.loopers.application.ranking

import com.loopers.domain.ranking.RankingWeightConfig
import java.time.ZonedDateTime

/** 가중치 설정 유스케이스 출력. 가중치는 admin이 입력한 논리값으로 되돌려 노출한다 (저장 스케일 ÷10). */
data class RankingWeightResult(
    val version: String,
    val viewWeight: Long,
    val likeWeight: Long,
    val orderWeight: Long,
    val status: String,
    val createdAt: ZonedDateTime,
    val activatedAt: ZonedDateTime?,
) {
    companion object {
        private const val STORAGE_SCALE = 10L

        fun from(config: RankingWeightConfig): RankingWeightResult = RankingWeightResult(
            version = config.version,
            viewWeight = config.viewWeight / STORAGE_SCALE,
            likeWeight = config.likeWeight / STORAGE_SCALE,
            orderWeight = config.orderWeight / STORAGE_SCALE,
            status = config.status.name,
            createdAt = config.createdAt,
            activatedAt = config.activatedAt,
        )
    }
}
