package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.model.WaitToken
import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.domain.waitingqueue.port.WaitingQueuePort

/**
 * 대기열 진입(UC1). 기존 위치를 제거한 뒤 현재 시각으로 다시 등록해 항상 "맨 뒤"로 재진입시키고,
 * 새 대기열 토큰을 발급한다.
 */
class QueueEntryService(
    private val waitingQueue: WaitingQueuePort,
    private val signer: TokenSignerPort,
) {
    fun enter(topic: QueueTopic, userId: Long, now: Long): WaitToken {
        waitingQueue.remove(topic, userId)
        waitingQueue.enqueue(topic, userId, now)
        return WaitToken.issue(topic, userId, now, signer)
    }
}
