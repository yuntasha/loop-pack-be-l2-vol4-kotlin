package com.loopers.application.ranking

import com.loopers.domain.ranking.RankingRepositoryPort
import com.loopers.domain.ranking.RankingService
import com.loopers.domain.ranking.RankingWeightConfigRepositoryPort
import com.loopers.domain.ranking.RankingWeightKvPort
import com.loopers.domain.ranking.RankingWeightService
import com.loopers.domain.ranking.RankingWeightViewPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RankingConfig {
    @Bean
    fun rankingService(
        rankingRepositoryPort: RankingRepositoryPort,
        rankingWeightViewPort: RankingWeightViewPort,
    ): RankingService = RankingService(rankingRepositoryPort, rankingWeightViewPort)

    @Bean
    fun rankingWeightService(
        rankingWeightConfigRepositoryPort: RankingWeightConfigRepositoryPort,
        rankingWeightKvPort: RankingWeightKvPort,
    ): RankingWeightService = RankingWeightService(rankingWeightConfigRepositoryPort, rankingWeightKvPort)
}
