package com.loopers.infrastructure.waitingqueue

import com.loopers.application.waitingqueue.UpdateConfigCommand
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.interfaces.api.waitingqueue.QueueAdminApplicationServicePort
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class QueueAdminConfigIntegrationTest @Autowired constructor(
    private val adminService: QueueAdminApplicationServicePort,
    private val jpaRepository: QueueConfigJpaRepository,
    private val cache: RedisQueueConfigCache,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    private val topic = QueueTopic("order")

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    @DisplayName("updateConfig 는 DB 원본과 Redis 캐시에 write-through 한다.")
    @Test
    fun updatePersistsToDbAndCache() {
        adminService.updateConfig(UpdateConfigCommand(topic = "order", admitCountPerPoll = 7))

        assertThat(jpaRepository.findByTopic("order")?.admitCountPerPoll).isEqualTo(7)
        assertThat(cache.read(topic)?.admitCountPerPoll).isEqualTo(7)
    }

    @DisplayName("설정이 없는 토픽을 조회하면 기본값을 반환한다.")
    @Test
    fun getReturnsDefaultWhenMissing() {
        val result = adminService.getConfig("order")

        assertThat(result.pollingIntervalMs).isEqualTo(3_000L)
        assertThat(result.admitCountPerPoll).isEqualTo(100)
        assertThat(result.admitWindowSec).isEqualTo(10)
        assertThat(result.accessTokenTtlSec).isEqualTo(30)
    }
}
