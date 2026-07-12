package com.loopers.infrastructure.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 대기열 설정 원본(DB). 다른 엔티티와 연관이 없으며 topic 에 유니크 인덱스만 부여한다(FK 없음).
 */
@Entity
@Table(
    name = "queue_config",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_queue_config_topic", columnNames = ["topic"]),
    ],
)
class QueueConfigEntity(
    @Column(name = "topic", nullable = false)
    val topic: String,

    @Column(name = "polling_interval_ms", nullable = false)
    var pollingIntervalMs: Long,

    @Column(name = "admit_count_per_poll", nullable = false)
    var admitCountPerPoll: Int,

    @Column(name = "admit_window_sec", nullable = false)
    var admitWindowSec: Int,

    @Column(name = "access_token_ttl_sec", nullable = false)
    var accessTokenTtlSec: Int,
) : BaseEntity() {
    fun update(config: QueueConfig) {
        this.pollingIntervalMs = config.pollingIntervalMs
        this.admitCountPerPoll = config.admitCountPerPoll
        this.admitWindowSec = config.admitWindowSec
        this.accessTokenTtlSec = config.accessTokenTtlSec
    }

    fun toDomain(): QueueConfig = QueueConfig(
        pollingIntervalMs = pollingIntervalMs,
        admitCountPerPoll = admitCountPerPoll,
        admitWindowSec = admitWindowSec,
        accessTokenTtlSec = accessTokenTtlSec,
    )

    companion object {
        fun from(topic: String, config: QueueConfig): QueueConfigEntity = QueueConfigEntity(
            topic = topic,
            pollingIntervalMs = config.pollingIntervalMs,
            admitCountPerPoll = config.admitCountPerPoll,
            admitWindowSec = config.admitWindowSec,
            accessTokenTtlSec = config.accessTokenTtlSec,
        )
    }
}
