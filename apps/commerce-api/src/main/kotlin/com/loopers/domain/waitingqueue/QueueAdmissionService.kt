package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.AdmitSummary
import com.loopers.domain.waitingqueue.port.AdmissionGatePort
import com.loopers.domain.waitingqueue.port.AdmissionMarkerPort
import com.loopers.domain.waitingqueue.port.QueueConfigPort
import com.loopers.domain.waitingqueue.port.WaitingQueuePort

/**
 * 승격 처리(UC6). 마스터 틱마다 모든 토픽을 순회하며, 폴링 주기가 경과한 토픽에 대해
 * 상위 N명을 꺼내(ZPOPMIN) 승격 마커를 남긴다. 토큰은 만들지 않는다(발급은 POST /queue/token).
 */
class QueueAdmissionService(
    private val waitingQueue: WaitingQueuePort,
    private val marker: AdmissionMarkerPort,
    private val config: QueueConfigPort,
    private val gate: AdmissionGatePort,
) {
    fun admitDueTopics(now: Long): AdmitSummary {
        var topicsProcessed = 0
        var totalAdmitted = 0

        for (topic in waitingQueue.topics()) {
            val cfg = config.get(topic)
            val lastAdmittedAt = gate.lastAdmittedAt(topic)
            if (lastAdmittedAt != null && now - lastAdmittedAt < cfg.pollingIntervalMs) continue

            val admitted = waitingQueue.popTop(topic, cfg.admitCountPerPoll)
            admitted.forEach { userId -> marker.mark(topic, userId, cfg.admitWindowSec) }
            gate.markAdmittedAt(topic, now)

            totalAdmitted += admitted.size
            topicsProcessed++
        }

        return AdmitSummary(topicsProcessed, totalAdmitted)
    }
}
