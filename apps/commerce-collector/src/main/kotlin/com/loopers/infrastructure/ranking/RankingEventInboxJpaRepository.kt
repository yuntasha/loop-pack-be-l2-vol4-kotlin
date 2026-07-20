package com.loopers.infrastructure.ranking

import org.springframework.data.jpa.repository.JpaRepository

interface RankingEventInboxJpaRepository : JpaRepository<RankingEventInboxEntity, String>
