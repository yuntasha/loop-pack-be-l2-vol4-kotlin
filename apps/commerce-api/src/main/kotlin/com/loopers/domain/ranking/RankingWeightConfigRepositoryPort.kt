package com.loopers.domain.ranking

interface RankingWeightConfigRepositoryPort {
    fun findByVersion(version: String): RankingWeightConfig?

    fun findAll(): List<RankingWeightConfig>

    fun findActive(): RankingWeightConfig?

    fun save(config: RankingWeightConfig): RankingWeightConfig
}
