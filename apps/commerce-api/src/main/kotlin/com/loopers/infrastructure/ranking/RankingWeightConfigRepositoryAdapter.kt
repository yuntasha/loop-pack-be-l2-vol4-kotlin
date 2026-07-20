package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.RankingWeightConfig
import com.loopers.domain.ranking.RankingWeightConfigRepositoryPort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class RankingWeightConfigRepositoryAdapter(
    private val rankingWeightConfigJpaRepository: RankingWeightConfigJpaRepository,
) : RankingWeightConfigRepositoryPort {
    override fun findByVersion(version: String): RankingWeightConfig? =
        rankingWeightConfigJpaRepository.findByIdOrNull(version)?.toDomain()

    override fun findAll(): List<RankingWeightConfig> =
        rankingWeightConfigJpaRepository.findAll().map { it.toDomain() }

    override fun findActive(): RankingWeightConfig? =
        rankingWeightConfigJpaRepository.findByStatus(PersistedRankingWeightStatus.ACTIVE)
            .firstOrNull()
            ?.toDomain()

    override fun save(config: RankingWeightConfig): RankingWeightConfig {
        val entity = rankingWeightConfigJpaRepository.findByIdOrNull(config.version)
            ?.apply { update(config) }
            ?: RankingWeightConfigEntity.from(config)
        return rankingWeightConfigJpaRepository.save(entity).toDomain()
    }
}
