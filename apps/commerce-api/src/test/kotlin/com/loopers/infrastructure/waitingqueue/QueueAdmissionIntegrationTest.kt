package com.loopers.infrastructure.waitingqueue

import com.loopers.application.waitingqueue.EnterCommand
import com.loopers.application.waitingqueue.PositionQuery
import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.interfaces.api.waitingqueue.QueueAdmissionApplicationServicePort
import com.loopers.interfaces.api.waitingqueue.QueueApplicationServicePort
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class QueueAdmissionIntegrationTest @Autowired constructor(
    private val queueApplicationService: QueueApplicationServicePort,
    private val admissionService: QueueAdmissionApplicationServicePort,
    private val markerStore: RedisAdmissionMarkerStore,
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

    @DisplayName("승격 후 마커가 생기고, 해당 사용자의 순번 조회는 ADMITTED 가 된다.")
    @Test
    fun promotesAndPositionBecomesAdmitted() {
        val enter = queueApplicationService.enter(EnterCommand(topic = "order", userId = 1L))

        admissionService.admitDueTopics(System.currentTimeMillis())

        assertThat(markerStore.exists(topic, 1L)).isTrue()
        val position = queueApplicationService.position(PositionQuery(enter.waitToken))
        assertThat(position.status).isEqualTo("ADMITTED")
    }

    @DisplayName("admitCountPerPoll 만큼만 승격되고 나머지는 대기열에 남는다.")
    @Test
    fun promotesOnlyTopN() {
        jpaRepository.save(
            QueueConfigEntity.from(
                "order",
                QueueConfig(pollingIntervalMs = 3_000L, admitCountPerPoll = 2, admitWindowSec = 10, accessTokenTtlSec = 30),
            ),
        )
        queueApplicationService.enter(EnterCommand("order", 1L))
        Thread.sleep(3)
        queueApplicationService.enter(EnterCommand("order", 2L))
        Thread.sleep(3)
        queueApplicationService.enter(EnterCommand("order", 3L))

        val summary = admissionService.admitDueTopics(System.currentTimeMillis())

        assertThat(summary.totalAdmitted).isEqualTo(2)
        assertThat(markerStore.exists(topic, 1L)).isTrue()
        assertThat(markerStore.exists(topic, 2L)).isTrue()
        assertThat(markerStore.exists(topic, 3L)).isFalse()
    }
}
