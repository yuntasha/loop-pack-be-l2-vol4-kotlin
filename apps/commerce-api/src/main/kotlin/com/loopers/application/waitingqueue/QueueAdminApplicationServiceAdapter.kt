package com.loopers.application.waitingqueue

import com.loopers.domain.waitingqueue.QueueConfigService
import com.loopers.domain.waitingqueue.model.QueueConfigPatch
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.interfaces.api.waitingqueue.QueueAdminApplicationServicePort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueueAdminApplicationServiceAdapter(
    private val queueConfigService: QueueConfigService,
) : QueueAdminApplicationServicePort {
    @Transactional
    override fun updateConfig(command: UpdateConfigCommand): QueueConfigResult {
        val merged = queueConfigService.update(
            topic = QueueTopic(command.topic),
            patch = QueueConfigPatch(
                pollingIntervalMs = command.pollingIntervalMs,
                admitCountPerPoll = command.admitCountPerPoll,
                admitWindowSec = command.admitWindowSec,
                accessTokenTtlSec = command.accessTokenTtlSec,
            ),
        )
        return QueueConfigResult.from(command.topic, merged)
    }

    @Transactional(readOnly = true)
    override fun getConfig(topic: String): QueueConfigResult =
        QueueConfigResult.from(topic, queueConfigService.get(QueueTopic(topic)))
}
