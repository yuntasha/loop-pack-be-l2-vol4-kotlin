package com.loopers.domain.waitingqueue.port

import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueTopic

/**
 * 토픽별 설정 아웃바운드 포트. 캐시 우선 → DB → 기본값 로직은 어댑터에 은닉한다.
 */
interface QueueConfigPort {
    fun get(topic: QueueTopic): QueueConfig

    /** 원본(DB) 저장 + 캐시 write-through. 캐시/DB 동기화는 어댑터에 은닉. */
    fun save(topic: QueueTopic, config: QueueConfig)
}
