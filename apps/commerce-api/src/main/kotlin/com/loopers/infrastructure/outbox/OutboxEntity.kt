package com.loopers.infrastructure.outbox

import com.loopers.domain.outbox.Outbox
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(
    name = "outbox",
    indexes = [
        Index(name = "idx_outbox_status_created_at", columnList = "status, created_at"),
        // 가중치 재계산(replay) 배치의 topic + occurred_at 범위 조회용
        Index(name = "idx_outbox_topic_occurred_at", columnList = "topic, occurred_at"),
    ],
)
class OutboxEntity(
    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    val eventId: String,

    @Column(name = "topic", nullable = false, updatable = false)
    val topic: String,

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT", updatable = false)
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: PersistedOutboxStatus,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: ZonedDateTime,

    @Column(name = "published_at")
    var publishedAt: ZonedDateTime? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: ZonedDateTime

    @PrePersist
    fun prePersist() {
        createdAt = ZonedDateTime.now()
    }

    fun markPublished() {
        status = PersistedOutboxStatus.PUBLISHED
        publishedAt = ZonedDateTime.now()
    }

    fun toDomain(): Outbox = Outbox(
        id = id,
        eventId = eventId,
        topic = topic,
        payload = payload,
        status = status.toDomain(),
        occurredAt = occurredAt,
    )

    companion object {
        fun from(outbox: Outbox): OutboxEntity = OutboxEntity(
            eventId = outbox.eventId,
            topic = outbox.topic,
            payload = outbox.payload,
            status = PersistedOutboxStatus.from(outbox.status),
            occurredAt = outbox.occurredAt,
        )
    }
}
