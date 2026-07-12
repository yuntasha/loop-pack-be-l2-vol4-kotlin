package com.loopers.infrastructure.waitingqueue

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.AdmissionGatePort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

/**
 * 토픽별 마지막 승격 시각 저장소(queue:lastAdmit). 폴링 주기 게이트에 사용된다.
 */
@Component
class RedisAdmissionGateStore(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
) : AdmissionGatePort {
    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    override fun lastAdmittedAt(topic: QueueTopic): Long? =
        master.opsForValue().get(key(topic))?.toLongOrNull()

    override fun markAdmittedAt(topic: QueueTopic, now: Long) {
        master.opsForValue().set(key(topic), now.toString())
    }

    companion object {
        private fun key(topic: QueueTopic) = "queue:lastAdmit:${topic.value}"
    }
}
