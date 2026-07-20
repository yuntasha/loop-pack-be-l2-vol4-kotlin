package com.loopers.interfaces.consumer

import com.loopers.domain.ranking.RankingBoard
import com.loopers.infrastructure.ranking.RankingEventInboxJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@SpringBootTest
class RankingEventConsumerE2ETest @Autowired constructor(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val rankingEventInboxJpaRepository: RankingEventInboxJpaRepository,
    private val redisTemplate: RedisTemplate<*, *>,
    private val kafkaListenerEndpointRegistry: KafkaListenerEndpointRegistry,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    @Value("\${product-metric.topic}")
    private lateinit var topic: String

    @Suppress("UNCHECKED_CAST")
    private val redis = redisTemplate as RedisTemplate<String, String>

    // auto.offset.reset=latest라, 신규 그룹(ranking-collector)이 파티션을 할당받기 전에 발행된 메시지는 유실된다.
    // 발행 전에 모든 리스너 컨테이너의 파티션 할당을 보장한다.
    @BeforeEach
    fun waitForPartitionAssignment() {
        awaitUntil {
            kafkaListenerEndpointRegistry.listenerContainers.all { container ->
                container.assignedPartitions?.isNotEmpty() == true
            }
        }
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
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

    // 컷오프(23:50) 근처 시각이면 이중 적재로 검증 대상 키가 달라지므로, 낮 시간대로 고정한다
    private fun daytimeOccurredAt(): ZonedDateTime =
        ZonedDateTime.now(ZoneId.of("Asia/Seoul")).withHour(12).withMinute(0)

    @DisplayName("product-metric 토픽에 LIKE 메시지를 발행하면, 당일 랭킹 ZSET에 +50이 반영되고 Inbox에 기록된다.")
    @Test
    fun reflectsRankingScore_whenMessagePublished() {
        val occurredAt = daytimeOccurredAt()
        // 같은 토픽을 쓰는 다른 E2E(ProductMetricConsumerE2ETest)의 메시지도 랭킹 컨슈머가 소비하므로,
        // 고정 productId를 쓰면 잔여 점수가 누적된다. 테스트 전용 고유 id로 격리한다.
        val productId = uniqueProductId()
        val payload = ProductMetricPayload(
            eventId = UUID.randomUUID().toString(),
            productId = productId,
            type = "LIKE",
            delta = 1L,
            occurredAt = occurredAt,
        )

        kafkaTemplate.send(topic, payload)

        awaitUntil { rankingEventInboxRepositoryContains(payload.eventId) }
        // boards KV가 없으므로 기본 v1 보드에 적재된다
        val allKey = RankingBoard.allOf("v1", occurredAt.toLocalDate()).key()
        val snapshotKey = RankingBoard.snapshotOf("v1", occurredAt.toLocalDate()).key()
        assertThat(redis.opsForZSet().score(allKey, productId.toString())).isEqualTo(50.0)
        assertThat(redis.opsForZSet().score(snapshotKey, productId.toString())).isEqualTo(50.0)
    }

    @DisplayName("동일 eventId의 메시지가 중복 발행되어도, 랭킹 점수에는 한 번만 반영된다.")
    @Test
    fun ignoresDuplicateEvent_whenSameEventIdPublishedTwice() {
        val occurredAt = daytimeOccurredAt()
        val productId = uniqueProductId()
        val payload = ProductMetricPayload(
            eventId = UUID.randomUUID().toString(),
            productId = productId,
            type = "VIEW",
            delta = 1L,
            occurredAt = occurredAt,
        )

        kafkaTemplate.send(topic, payload)
        awaitUntil { rankingEventInboxRepositoryContains(payload.eventId) }

        kafkaTemplate.send(topic, payload)
        Thread.sleep(8_000L) // fetch.max.wait.ms(5s) 배치 폴링 주기를 감안해, 중복 메시지가 실제로 소비될 시간을 준다

        val allKey = RankingBoard.allOf("v1", occurredAt.toLocalDate()).key()
        assertThat(redis.opsForZSet().score(allKey, productId.toString())).isEqualTo(10.0)
    }

    private fun rankingEventInboxRepositoryContains(eventId: String): Boolean =
        rankingEventInboxJpaRepository.existsById(eventId)

    private fun uniqueProductId(): Long = System.nanoTime()
}
