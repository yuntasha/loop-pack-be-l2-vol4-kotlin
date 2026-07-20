package com.loopers.infrastructure.ranking

import org.springframework.data.jpa.repository.JpaRepository

interface RankingWeightConfigJpaRepository : JpaRepository<RankingWeightConfigEntity, String> {
    fun findByStatus(status: PersistedRankingWeightStatus): List<RankingWeightConfigEntity>
}
