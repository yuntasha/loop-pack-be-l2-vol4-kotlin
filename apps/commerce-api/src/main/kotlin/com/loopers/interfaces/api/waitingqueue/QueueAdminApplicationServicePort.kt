package com.loopers.interfaces.api.waitingqueue

import com.loopers.application.waitingqueue.QueueConfigResult
import com.loopers.application.waitingqueue.UpdateConfigCommand

/**
 * 대기열 관리자 인바운드 포트. 설정 조회/변경.
 */
interface QueueAdminApplicationServicePort {
    fun updateConfig(command: UpdateConfigCommand): QueueConfigResult

    fun getConfig(topic: String): QueueConfigResult
}
