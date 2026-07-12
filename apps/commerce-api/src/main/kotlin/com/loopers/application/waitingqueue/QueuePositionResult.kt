package com.loopers.application.waitingqueue

import com.loopers.domain.waitingqueue.model.QueuePosition

/** 순번 조회 결과. 도메인 enum(QueueStatus)은 String 으로 변환해 계층을 넘긴다. */
data class QueuePositionResult(
    val topic: String,
    val status: String,
    val rank: Long,
    val ahead: Long,
    val estimatedWaitSeconds: Int,
    val nextPollAfterSeconds: Int,
    val admitExpiresInSeconds: Int,
) {
    companion object {
        fun from(position: QueuePosition): QueuePositionResult = QueuePositionResult(
            topic = position.topic.value,
            status = position.status.name,
            rank = position.rank,
            ahead = position.ahead,
            estimatedWaitSeconds = position.estimatedWaitSeconds,
            nextPollAfterSeconds = position.nextPollAfterSeconds,
            admitExpiresInSeconds = position.admitExpiresInSeconds,
        )
    }
}
