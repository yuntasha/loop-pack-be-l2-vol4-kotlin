package com.loopers.infrastructure.waitingqueue

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.WaitingQueuePort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis Sorted Set 기반 대기열 어댑터.
 * - member = userId, score = 등록 시각(epoch ms) → 순번은 ZRANK(O(log N)).
 * - 최초 등록 시 `queue:topics` 에 토픽을 추가해 스케줄러 순회 대상을 파악하게 한다.
 * - 승격은 ZPOPMIN 으로 상위 N명을 원자적으로 꺼낸다.
 * 순번의 정합성(ZADD 직후 ZRANK)이 중요하므로 읽기/쓰기 모두 master 템플릿을 사용한다.
 */
@Component
class RedisWaitingQueueAdapter(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
) : WaitingQueuePort {
    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    override fun enqueue(topic: QueueTopic, userId: Long, score: Long) {
        master.opsForZSet().add(waitKey(topic), userId.toString(), score.toDouble())
        master.opsForSet().add(TOPICS_KEY, topic.value)
    }

    override fun rank(topic: QueueTopic, userId: Long): Long? =
        master.opsForZSet().rank(waitKey(topic), userId.toString())

    override fun remove(topic: QueueTopic, userId: Long) {
        master.opsForZSet().remove(waitKey(topic), userId.toString())
    }

    override fun popTop(topic: QueueTopic, count: Int): List<Long> {
        if (count <= 0) return emptyList()
        val popped = master.opsForZSet().popMin(waitKey(topic), count.toLong()) ?: return emptyList()
        return popped.mapNotNull { it.value?.toLongOrNull() }
    }

    override fun topics(): Set<QueueTopic> =
        master.opsForSet().members(TOPICS_KEY).orEmpty().map { QueueTopic(it) }.toSet()

    companion object {
        const val TOPICS_KEY = "queue:topics"

        private fun waitKey(topic: QueueTopic) = "queue:wait:${topic.value}"
    }
}
