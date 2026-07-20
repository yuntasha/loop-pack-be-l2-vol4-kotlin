package com.loopers.interfaces.api.ranking

import com.loopers.application.ranking.RankingWeightResult
import com.loopers.application.ranking.RegisterRankingWeightCommand
import java.time.ZonedDateTime

class RankingAdminV1Dto {
    data class RegisterWeightRequest(
        val version: String,
        val viewWeight: Long,
        val likeWeight: Long,
        val orderWeight: Long,
    ) {
        fun toCommand(): RegisterRankingWeightCommand = RegisterRankingWeightCommand(
            version = version,
            viewWeight = viewWeight,
            likeWeight = likeWeight,
            orderWeight = orderWeight,
        )
    }

    data class WeightResponse(
        val version: String,
        val viewWeight: Long,
        val likeWeight: Long,
        val orderWeight: Long,
        val status: String,
        val createdAt: ZonedDateTime,
        val activatedAt: ZonedDateTime?,
    ) {
        companion object {
            fun from(result: RankingWeightResult): WeightResponse = WeightResponse(
                version = result.version,
                viewWeight = result.viewWeight,
                likeWeight = result.likeWeight,
                orderWeight = result.orderWeight,
                status = result.status,
                createdAt = result.createdAt,
                activatedAt = result.activatedAt,
            )
        }
    }
}
