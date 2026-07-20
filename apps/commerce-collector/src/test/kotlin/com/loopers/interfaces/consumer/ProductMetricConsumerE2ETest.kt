package com.loopers.interfaces.consumer

import com.loopers.infrastructure.metric.EventHandledJpaRepository
import com.loopers.infrastructure.metric.ProductMetricId
import com.loopers.infrastructure.metric.ProductMetricJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import java.time.ZonedDateTime
import java.util.UUID

@SpringBootTest
class ProductMetricConsumerE2ETest @Autowired constructor(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val productMetricJpaRepository: ProductMetricJpaRepository,
    private val eventHandledJpaRepository: EventHandledJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    @Value("\${product-metric.topic}")
    private lateinit var topic: String

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        // 같은 토픽을 랭킹 컨슈머도 구독하므로, 이 테스트의 메시지가 Redis 랭킹 보드에 남긴 점수도 정리한다
        redisCleanUp.truncateAll()
    }

    private fun awaitUntil(timeoutMs: Long = 30_000L, intervalMs: Long = 300L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        check(condition()) { "조건이 ${timeoutMs}ms 안에 충족되지 않았다." }
    }

    @DisplayName("product-metric 토픽에 메시지를 발행하면, product_metrics 테이블에 count가 반영된다.")
    @Test
    fun accumulatesMetric_whenMessagePublished() {
        val productId = 1L
        val payload = ProductMetricPayload(
            eventId = UUID.randomUUID().toString(),
            productId = productId,
            type = "LIKE",
            delta = 1L,
            occurredAt = ZonedDateTime.now(),
        )

        kafkaTemplate.send(topic, payload)

        awaitUntil {
            productMetricJpaRepository.findById(ProductMetricId(productId, "LIKE")).isPresent
        }
        assertThat(productMetricJpaRepository.findById(ProductMetricId(productId, "LIKE")).get().count).isEqualTo(1L)
        assertThat(eventHandledJpaRepository.existsById(payload.eventId)).isTrue()
    }

    @DisplayName("동일 eventId의 메시지가 중복 발행되어도, count에는 한 번만 반영된다.")
    @Test
    fun ignoresDuplicateEvent_whenSameEventIdPublishedTwice() {
        val productId = 2L
        val eventId = UUID.randomUUID().toString()
        val payload = ProductMetricPayload(
            eventId = eventId,
            productId = productId,
            type = "VIEW",
            delta = 1L,
            occurredAt = ZonedDateTime.now(),
        )

        kafkaTemplate.send(topic, payload)
        awaitUntil { eventHandledJpaRepository.existsById(eventId) }

        kafkaTemplate.send(topic, payload)
        Thread.sleep(8_000L) // fetch.max.wait.ms(5s) 배치 폴링 주기를 감안해, 중복 메시지가 실제로 소비될 시간을 준다

        assertThat(productMetricJpaRepository.findById(ProductMetricId(productId, "VIEW")).get().count).isEqualTo(1L)
    }
}
