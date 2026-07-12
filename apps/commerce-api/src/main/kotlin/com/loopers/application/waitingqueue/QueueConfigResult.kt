package com.loopers.application.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueConfig

/** 대기열 설정 조회/변경 결과. */
data class QueueConfigResult(
    val topic: String,
    val pollingIntervalMs: Long,
    val admitCountPerPoll: Int,
    val admitWindowSec: Int,
    val accessTokenTtlSec: Int,
) {
    companion object {
        fun from(topic: String, config: QueueConfig): QueueConfigResult = QueueConfigResult(
            topic = topic,
            pollingIntervalMs = config.pollingIntervalMs,
            admitCountPerPoll = config.admitCountPerPoll,
            admitWindowSec = config.admitWindowSec,
            accessTokenTtlSec = config.accessTokenTtlSec,
        )
    }
}
