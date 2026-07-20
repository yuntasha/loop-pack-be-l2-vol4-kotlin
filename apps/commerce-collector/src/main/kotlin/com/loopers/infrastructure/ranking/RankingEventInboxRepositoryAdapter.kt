package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.RankingEventInboxRepositoryPort
import org.springframework.stereotype.Component

@Component
class RankingEventInboxRepositoryAdapter(
    private val rankingEventInboxJpaRepository: RankingEventInboxJpaRepository,
) : RankingEventInboxRepositoryPort {
    override fun isHandled(eventId: String): Boolean = rankingEventInboxJpaRepository.existsById(eventId)

    override fun markHandled(eventId: String) {
        rankingEventInboxJpaRepository.save(RankingEventInboxEntity(eventId = eventId))
    }
}
