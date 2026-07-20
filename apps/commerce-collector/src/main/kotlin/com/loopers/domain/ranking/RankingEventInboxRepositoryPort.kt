package com.loopers.domain.ranking

interface RankingEventInboxRepositoryPort {
    fun isHandled(eventId: String): Boolean

    fun markHandled(eventId: String)
}
