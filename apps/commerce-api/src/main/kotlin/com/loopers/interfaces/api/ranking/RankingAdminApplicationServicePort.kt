package com.loopers.interfaces.api.ranking

import com.loopers.application.ranking.RankingWeightResult
import com.loopers.application.ranking.RegisterRankingWeightCommand

interface RankingAdminApplicationServicePort {
    fun getWeights(): List<RankingWeightResult>

    fun registerWeights(command: RegisterRankingWeightCommand): RankingWeightResult

    fun activateWeights(version: String): RankingWeightResult

    fun retireWeights(version: String): RankingWeightResult

    fun reopenWeights(version: String): RankingWeightResult
}
