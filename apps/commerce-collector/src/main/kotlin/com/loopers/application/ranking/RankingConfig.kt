package com.loopers.application.ranking

import com.loopers.domain.ranking.RankingEventInboxRepositoryPort
import com.loopers.domain.ranking.RankingRepositoryPort
import com.loopers.domain.ranking.RankingService
import com.loopers.domain.ranking.RankingWeightBoardsPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class RankingConfig {
    // 동결 게이트가 "처리 시각"을 기준으로 하므로 시스템 Clock을 주입한다 (테스트에서는 고정 Clock)
    @Bean
    fun rankingService(
        rankingRepositoryPort: RankingRepositoryPort,
        rankingEventInboxRepositoryPort: RankingEventInboxRepositoryPort,
        rankingWeightBoardsPort: RankingWeightBoardsPort,
    ): RankingService =
        RankingService(rankingRepositoryPort, rankingEventInboxRepositoryPort, rankingWeightBoardsPort, Clock.systemDefaultZone())
}
