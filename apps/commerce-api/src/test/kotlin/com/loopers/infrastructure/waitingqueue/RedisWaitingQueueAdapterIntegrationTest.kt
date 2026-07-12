package com.loopers.infrastructure.waitingqueue

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate

@Suppress("UNCHECKED_CAST")
@SpringBootTest
class RedisWaitingQueueAdapterIntegrationTest @Autowired constructor(
    private val adapter: RedisWaitingQueueAdapter,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
    private val redisCleanUp: RedisCleanUp,
) {
    private val redis = masterTemplate as RedisTemplate<String, String>
    private val topic = QueueTopic("order")

    @AfterEach
    fun tearDown() {
        redisCleanUp.truncateAll()
    }

    @DisplayName("enqueue 하면 0-based 순번(rank)을 반환한다.")
    @Test
    fun enqueueAndRank() {
        adapter.enqueue(topic, userId = 1L, score = 1_000L)
        adapter.enqueue(topic, userId = 2L, score = 2_000L)

        assertThat(adapter.rank(topic, 1L)).isEqualTo(0L)
        assertThat(adapter.rank(topic, 2L)).isEqualTo(1L)
    }

    @DisplayName("enqueue 시 queue:topics 에 토픽이 등록된다.")
    @Test
    fun registersTopic() {
        adapter.enqueue(topic, userId = 1L, score = 1_000L)

        val topics = redis.opsForSet().members(RedisWaitingQueueAdapter.TOPICS_KEY)
        assertThat(topics).contains("order")
    }

    @DisplayName("remove 하면 순번 조회 시 null 을 반환한다.")
    @Test
    fun removeThenRankNull() {
        adapter.enqueue(topic, userId = 1L, score = 1_000L)

        adapter.remove(topic, 1L)

        assertThat(adapter.rank(topic, 1L)).isNull()
    }

    @DisplayName("더 큰 score 로 재등록하면 순번이 뒤로 밀린다(맨 뒤 재진입).")
    @Test
    fun reEnqueueMovesToBack() {
        adapter.enqueue(topic, userId = 1L, score = 1_000L)
        adapter.enqueue(topic, userId = 2L, score = 2_000L)

        adapter.enqueue(topic, userId = 1L, score = 3_000L)

        assertThat(adapter.rank(topic, 2L)).isEqualTo(0L)
        assertThat(adapter.rank(topic, 1L)).isEqualTo(1L)
    }
}
