package com.loopers.infrastructure.waitingqueue

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.AccessTokenStorePort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 입장 토큰 백업 저장소(queue:access). 발급 시 TTL(accessTokenTtlSec)과 함께 SET 한다.
 */
@Component
class RedisAccessTokenStore(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
) : AccessTokenStorePort {
    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    override fun store(topic: QueueTopic, userId: Long, token: String, ttlSec: Int) {
        master.opsForValue().set(accessKey(topic, userId), token, Duration.ofSeconds(ttlSec.toLong()))
    }

    override fun get(topic: QueueTopic, userId: Long): String? =
        master.opsForValue().get(accessKey(topic, userId))

    companion object {
        fun accessKey(topic: QueueTopic, userId: Long) = "queue:access:${topic.value}:$userId"
    }
}
