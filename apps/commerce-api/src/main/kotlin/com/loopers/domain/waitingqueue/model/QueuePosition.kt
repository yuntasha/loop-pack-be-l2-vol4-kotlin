package com.loopers.domain.waitingqueue.model

import kotlin.math.ceil
import kotlin.math.max

/**
 * 대기열 순번 스냅샷. ETA/계단식 폴링 간격 계산을 캡슐화한다.
 *
 * @property rank 1-based 순번(내 앞 인원 + 1)
 * @property ahead 내 앞의 대기 인원
 * @property estimatedWaitSeconds 예상 대기 시간
 * @property nextPollAfterSeconds 다음 폴링까지 권장 대기 시간(클라이언트 힌트)
 * @property admitExpiresInSeconds 승격 상태일 때 입장 토큰 발급 가능 잔여 시간
 */
data class QueuePosition(
    val topic: QueueTopic,
    val status: QueueStatus,
    val rank: Long,
    val ahead: Long,
    val estimatedWaitSeconds: Int,
    val nextPollAfterSeconds: Int,
    val admitExpiresInSeconds: Int,
) {
    companion object {
        fun waiting(topic: QueueTopic, rank: Long, ahead: Long, config: QueueConfig): QueuePosition {
            val eta = calcEta(ahead, config)
            return QueuePosition(
                topic = topic,
                status = QueueStatus.WAITING,
                rank = rank,
                ahead = ahead,
                estimatedWaitSeconds = eta,
                nextPollAfterSeconds = calcNextPoll(eta),
                admitExpiresInSeconds = 0,
            )
        }

        fun admitted(topic: QueueTopic, admitExpiresInSeconds: Int): QueuePosition = QueuePosition(
            topic = topic,
            status = QueueStatus.ADMITTED,
            rank = 0,
            ahead = 0,
            estimatedWaitSeconds = 0,
            nextPollAfterSeconds = 0,
            admitExpiresInSeconds = admitExpiresInSeconds,
        )

        fun expired(topic: QueueTopic): QueuePosition = QueuePosition(
            topic = topic,
            status = QueueStatus.EXPIRED,
            rank = 0,
            ahead = 0,
            estimatedWaitSeconds = 0,
            nextPollAfterSeconds = 0,
            admitExpiresInSeconds = 0,
        )

        /** eta = ceil(ahead / admitCountPerPoll) * (pollingIntervalMs / 1000) */
        private fun calcEta(ahead: Long, config: QueueConfig): Int {
            if (ahead <= 0) return 0
            val perPoll = max(1, config.admitCountPerPoll)
            val batches = ceil(ahead.toDouble() / perPoll).toLong()
            return (batches * (config.pollingIntervalMs / 1000)).toInt()
        }

        /** 대기가 길수록 폴링을 늦춰 서버 부하를 줄이는 계단식 힌트. */
        private fun calcNextPoll(eta: Int): Int = when {
            eta >= 300 -> 60
            eta >= 60 -> 12
            else -> 3
        }
    }
}
