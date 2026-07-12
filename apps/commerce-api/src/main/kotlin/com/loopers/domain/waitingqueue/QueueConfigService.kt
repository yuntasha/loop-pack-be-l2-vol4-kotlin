package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueConfigPatch
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.QueueConfigPort

/**
 * 대기열 설정 조회/변경(UC5). 캐시/DB 분기는 어댑터가 은닉하므로 포트만 호출한다.
 * update 는 현재 설정에 패치를 병합(부분 수정)해 저장한다.
 */
class QueueConfigService(
    private val config: QueueConfigPort,
) {
    fun get(topic: QueueTopic): QueueConfig = config.get(topic)

    fun update(topic: QueueTopic, patch: QueueConfigPatch): QueueConfig {
        val merged = config.get(topic).merge(patch)
        config.save(topic, merged)
        return merged
    }
}
