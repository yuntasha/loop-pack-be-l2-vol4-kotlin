package com.loopers.infrastructure.ranking

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

/**
 * 랭킹 컨슈머 전용 Inbox. metric 컨슈머의 event_handled와 같은 eventId를 서로 다른 그룹이 소비하므로
 * 테이블을 공유하면 한쪽 처리 기록이 다른 쪽 skip으로 오인된다 — 반드시 분리 보관한다.
 */
@Entity
@Table(name = "ranking_event_inbox")
class RankingEventInboxEntity(
    @Id
    @Column(name = "event_id", nullable = false)
    val eventId: String,

    @Column(name = "handled_at", nullable = false)
    val handledAt: ZonedDateTime = ZonedDateTime.now(),
)
