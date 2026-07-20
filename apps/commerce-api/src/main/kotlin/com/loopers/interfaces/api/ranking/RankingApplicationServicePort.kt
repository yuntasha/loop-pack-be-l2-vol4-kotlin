package com.loopers.interfaces.api.ranking

import com.loopers.application.ranking.RankingPageCommand
import com.loopers.application.ranking.RankingPageResult

interface RankingApplicationServicePort {
    fun getRankingPage(command: RankingPageCommand): RankingPageResult
}
