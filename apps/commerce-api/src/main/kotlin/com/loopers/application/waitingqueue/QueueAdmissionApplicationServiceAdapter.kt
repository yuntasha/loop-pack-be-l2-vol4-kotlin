package com.loopers.application.waitingqueue

import com.loopers.domain.waitingqueue.QueueAdmissionService
import com.loopers.interfaces.api.waitingqueue.QueueAdmissionApplicationServicePort
import org.springframework.stereotype.Service

@Service
class QueueAdmissionApplicationServiceAdapter(
    private val queueAdmissionService: QueueAdmissionService,
) : QueueAdmissionApplicationServicePort {
    override fun admitDueTopics(now: Long): AdmitSummaryResult =
        AdmitSummaryResult.from(queueAdmissionService.admitDueTopics(now))
}
