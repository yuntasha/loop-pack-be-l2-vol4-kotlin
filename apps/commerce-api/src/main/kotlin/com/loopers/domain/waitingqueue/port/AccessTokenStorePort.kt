package com.loopers.domain.waitingqueue.port

import com.loopers.domain.waitingqueue.model.QueueTopic

/**
 * 발급된 입장 토큰의 Redis 백업 저장소. 강제 만료/회수와 "1인 1패스" 보장을 위해 사용한다.
 */
interface AccessTokenStorePort {
    fun store(topic: QueueTopic, userId: Long, token: String, ttlSec: Int)

    fun get(topic: QueueTopic, userId: Long): String?
}
