package com.loopers.domain.waitingqueue.port

import com.loopers.domain.waitingqueue.model.QueueTopic

/**
 * 토픽별 폴링 주기 게이트. 마지막 승격 시각을 기록해 pollingIntervalMs 마다만 승격되게 한다.
 */
interface AdmissionGatePort {
    fun lastAdmittedAt(topic: QueueTopic): Long?

    fun markAdmittedAt(topic: QueueTopic, now: Long)
}
