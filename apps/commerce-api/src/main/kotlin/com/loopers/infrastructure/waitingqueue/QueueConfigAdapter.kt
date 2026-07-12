package com.loopers.infrastructure.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.QueueConfigPort
import org.springframework.stereotype.Component

/**
 * 설정 어댑터. 조회는 캐시 우선 → DB → 기본값, 저장은 DB 원본 + 캐시 write-through.
 * 캐시/DB 분기는 상위 계층에 은닉한다(인프라 투명).
 */
@Component
class QueueConfigAdapter(
    private val cache: RedisQueueConfigCache,
    private val jpaRepository: QueueConfigJpaRepository,
) : QueueConfigPort {
    override fun get(topic: QueueTopic): QueueConfig {
        cache.read(topic)?.let { return it }

        val config = jpaRepository.findByTopic(topic.value)?.toDomain() ?: QueueConfig.default()
        cache.write(topic, config)
        return config
    }

    override fun save(topic: QueueTopic, config: QueueConfig) {
        val entity = jpaRepository.findByTopic(topic.value)?.apply { update(config) }
            ?: QueueConfigEntity.from(topic.value, config)
        jpaRepository.save(entity)
        cache.write(topic, config)
    }
}
