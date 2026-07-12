package com.loopers.infrastructure.waitingqueue

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.AdmissionMarkerPort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 승격 마커 저장소. 스케줄러가 승격 시 TTL(admitWindowSec)과 함께 SET 하고,
 * 조회가 존재 여부로 상태를 판단한다.
 */
@Component
class RedisAdmissionMarkerStore(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
) : AdmissionMarkerPort {
    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    override fun exists(topic: QueueTopic, userId: Long): Boolean =
        master.hasKey(markerKey(topic, userId))

    override fun mark(topic: QueueTopic, userId: Long, ttlSec: Int) {
        master.opsForValue().set(markerKey(topic, userId), "1", Duration.ofSeconds(ttlSec.toLong()))
    }

    companion object {
        fun markerKey(topic: QueueTopic, userId: Long) = "queue:admitted:${topic.value}:$userId"
    }
}
