package com.loopers.infrastructure.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class QueueConfigAdapterIntegrationTest @Autowired constructor(
    private val adapter: QueueConfigAdapter,
    private val cache: RedisQueueConfigCache,
    private val jpaRepository: QueueConfigJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    private val topic = QueueTopic("order")

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    @DisplayName("캐시·DB 모두 없으면 기본값을 반환하고 캐시에 채운다.")
    @Test
    fun defaultWhenMissing() {
        val config = adapter.get(topic)

        assertThat(config).isEqualTo(QueueConfig.default())
        assertThat(cache.read(topic)).isEqualTo(QueueConfig.default())
    }

    @DisplayName("캐시 미스 시 DB 값을 읽어 반환하고 캐시에 채운다.")
    @Test
    fun loadsFromDbOnCacheMiss() {
        val stored = QueueConfig(pollingIntervalMs = 5_000L, admitCountPerPoll = 50, admitWindowSec = 20, accessTokenTtlSec = 60)
        jpaRepository.save(QueueConfigEntity.from(topic.value, stored))

        val config = adapter.get(topic)

        assertThat(config).isEqualTo(stored)
        assertThat(cache.read(topic)).isEqualTo(stored)
    }

    @DisplayName("캐시에 값이 있으면 DB 를 보지 않고 캐시 값을 우선한다.")
    @Test
    fun prefersCacheOverDb() {
        val cached = QueueConfig(pollingIntervalMs = 1_000L, admitCountPerPoll = 10, admitWindowSec = 5, accessTokenTtlSec = 15)
        val inDb = QueueConfig(pollingIntervalMs = 9_000L, admitCountPerPoll = 90, admitWindowSec = 90, accessTokenTtlSec = 90)
        cache.write(topic, cached)
        jpaRepository.save(QueueConfigEntity.from(topic.value, inDb))

        val config = adapter.get(topic)

        assertThat(config).isEqualTo(cached)
    }
}
