package com.loopers.interfaces.api.waitingqueue

import com.loopers.application.waitingqueue.QueueConfigResult
import com.loopers.application.waitingqueue.UpdateConfigCommand

class QueueAdminV1Dto {
    /** 설정 부분 수정 요청. 넘어온 필드만 갱신된다. */
    data class UpdateConfigRequest(
        val pollingIntervalMs: Long? = null,
        val admitCountPerPoll: Int? = null,
        val admitWindowSec: Int? = null,
        val accessTokenTtlSec: Int? = null,
    ) {
        fun toCommand(topic: String): UpdateConfigCommand = UpdateConfigCommand(
            topic = topic,
            pollingIntervalMs = pollingIntervalMs,
            admitCountPerPoll = admitCountPerPoll,
            admitWindowSec = admitWindowSec,
            accessTokenTtlSec = accessTokenTtlSec,
        )
    }

    data class ConfigResponse(
        val topic: String,
        val pollingIntervalMs: Long,
        val admitCountPerPoll: Int,
        val admitWindowSec: Int,
        val accessTokenTtlSec: Int,
    ) {
        companion object {
            fun from(result: QueueConfigResult): ConfigResponse = ConfigResponse(
                topic = result.topic,
                pollingIntervalMs = result.pollingIntervalMs,
                admitCountPerPoll = result.admitCountPerPoll,
                admitWindowSec = result.admitWindowSec,
                accessTokenTtlSec = result.accessTokenTtlSec,
            )
        }
    }
}
