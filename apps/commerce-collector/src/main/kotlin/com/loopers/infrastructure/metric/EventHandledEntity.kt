package com.loopers.infrastructure.metric

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(name = "event_handled")
class EventHandledEntity(
    @Id
    @Column(name = "event_id", nullable = false)
    val eventId: String,

    @Column(name = "handled_at", nullable = false)
    val handledAt: ZonedDateTime = ZonedDateTime.now(),
)
