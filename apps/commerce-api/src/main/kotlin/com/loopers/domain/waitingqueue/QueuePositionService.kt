package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.QueuePosition
import com.loopers.domain.waitingqueue.model.WaitToken
import com.loopers.domain.waitingqueue.port.AdmissionMarkerPort
import com.loopers.domain.waitingqueue.port.QueueConfigPort
import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.domain.waitingqueue.port.WaitingQueuePort

/**
 * 순번/예상시간 조회(UC2). 순수 조회 — 토큰을 발급하거나 상태를 바꾸지 않는다.
 * 승격 마커가 있으면 ADMITTED, 대기열에 없으면 EXPIRED, 그 외 WAITING.
 */
class QueuePositionService(
    private val waitingQueue: WaitingQueuePort,
    private val marker: AdmissionMarkerPort,
    private val config: QueueConfigPort,
    private val signer: TokenSignerPort,
) {
    fun position(rawWaitToken: String): QueuePosition {
        val token = WaitToken.parse(rawWaitToken, signer)
        val cfg = config.get(token.topic)

        if (marker.exists(token.topic, token.userId)) {
            return QueuePosition.admitted(token.topic, cfg.admitWindowSec)
        }

        val zeroBasedRank = waitingQueue.rank(token.topic, token.userId)
            ?: return QueuePosition.expired(token.topic)

        return QueuePosition.waiting(
            topic = token.topic,
            rank = zeroBasedRank + 1,
            ahead = zeroBasedRank,
            config = cfg,
        )
    }
}
