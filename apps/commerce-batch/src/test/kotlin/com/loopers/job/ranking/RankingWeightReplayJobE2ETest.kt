package com.loopers.job.ranking

import com.loopers.batch.job.ranking.RankingWeightReplayJobConfig
import com.loopers.config.redis.RedisConfig
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.test.JobLauncherTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@SpringBootTest
@SpringBatchTest
@TestPropertySource(properties = ["spring.batch.job.name=${RankingWeightReplayJobConfig.JOB_NAME}"])
class RankingWeightReplayJobE2ETest @Autowired constructor(
    // IDE 정적 분석 상 [SpringBatchTest] 의 주입보다 [SpringBootTest] 의 주입이 우선되어, 해당 컴포넌트는 없으므로 오류처럼 보일 수 있음.
    // [SpringBatchTest] 자체가 Scope 기반으로 주입하기 때문에 정상 동작함.
    private val jobLauncherTestUtils: JobLauncherTestUtils,
    @param:Qualifier(RankingWeightReplayJobConfig.JOB_NAME) private val job: Job,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) masterTemplate: RedisTemplate<*, *>,
    private val jdbcTemplate: JdbcTemplate,
    private val redisCleanUp: RedisCleanUp,
) {
    @Suppress("UNCHECKED_CAST")
    private val redis = masterTemplate as RedisTemplate<String, String>

    private val zone = ZoneId.of("Asia/Seoul")

    // 자정(00:00~00:10) 부근 실행은 운영 절차상 금지 구간이라 테스트도 상정하지 않는다
    private val cutoff: ZonedDateTime = ZonedDateTime.now(zone)
    private val today: LocalDate = cutoff.toLocalDate()
    private val yesterday: LocalDate = today.minusDays(1)

    private val v2AllKey get() = "ranking:all:v2:${today.format(DateTimeFormatter.BASIC_ISO_DATE)}"
    private val v2SnapshotKey get() = "ranking:snapshot:v2:${today.format(DateTimeFormatter.BASIC_ISO_DATE)}"
    private val statusKey = "ranking:replay:status:v2"

    // outbox/ranking_weight_config 엔티티는 commerce-api 소유라 배치 테스트 컨텍스트엔 없다 - 스키마를 직접 준비한다
    @BeforeEach
    fun setUpSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS outbox (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                event_id VARCHAR(255) NOT NULL,
                topic VARCHAR(255) NOT NULL,
                payload TEXT NOT NULL,
                status VARCHAR(16) NOT NULL,
                occurred_at DATETIME(6) NOT NULL,
                published_at DATETIME(6) NULL,
                created_at DATETIME(6) NOT NULL,
                INDEX idx_outbox_topic_occurred_at (topic, occurred_at)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS ranking_weight_config (
                version VARCHAR(10) PRIMARY KEY,
                view_weight BIGINT NOT NULL,
                like_weight BIGINT NOT NULL,
                order_weight BIGINT NOT NULL,
                status VARCHAR(20) NOT NULL,
                created_at DATETIME(6) NOT NULL,
                activated_at DATETIME(6) NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.update("DELETE FROM outbox")
        jdbcTemplate.update("DELETE FROM ranking_weight_config")
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.update("DELETE FROM outbox")
        jdbcTemplate.update("DELETE FROM ranking_weight_config")
        redisCleanUp.truncateAll()
    }

    private fun jobParameters(version: String = "v2"): JobParameters = JobParametersBuilder()
        .addString("version", version)
        .addString("cutoffAt", cutoff.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        .addLong("runId", System.nanoTime())
        .toJobParameters()

    private fun insertConfig(version: String, view: Long, like: Long, order: Long, status: String) {
        jdbcTemplate.update(
            "INSERT INTO ranking_weight_config (version, view_weight, like_weight, order_weight, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            version,
            view,
            like,
            order,
            status,
            ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime(),
        )
    }

    private fun insertOutbox(
        productId: Long,
        type: String,
        delta: Long,
        occurredAt: ZonedDateTime,
        eventId: String = UUID.randomUUID().toString(),
        topic: String = "product.metric.v1",
    ): String {
        val payload =
            """{"eventId":"$eventId","productId":$productId,"type":"$type","delta":$delta,"occurredAt":"${
                occurredAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }"}"""
        val occurredAtUtc = occurredAt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        jdbcTemplate.update(
            "INSERT INTO outbox (event_id, topic, payload, status, occurred_at, created_at) VALUES (?, ?, ?, 'PUBLISHED', ?, ?)",
            eventId,
            topic,
            payload,
            occurredAtUtc,
            occurredAtUtc,
        )
        return eventId
    }

    private fun yesterdayAt(hour: Int): ZonedDateTime = yesterday.atTime(hour, 0).atZone(zone)

    // 자정 직후 실행이 아니라는 전제 하에 항상 "오늘 & 컷오프 이전"인 시각
    private fun todayBeforeCutoff(): ZonedDateTime = cutoff.minusMinutes(1)

    @DisplayName("어제분은 ×0.1 이월분으로, 오늘분은 원 점수로 v2 보드(all/snapshot)에 재계산되고, 완료 시 DONE + active KV flip + config ACTIVE 전이까지 수행된다.")
    @Test
    fun replaysOutbox_andFlipsActiveVersion() {
        jobLauncherTestUtils.job = job
        insertConfig("v1", 10L, 50L, 500L, "ACTIVE")
        insertConfig("v2", 20L, 80L, 400L, "PREPARING")
        insertOutbox(productId = 101L, type = "LIKE", delta = 1L, occurredAt = yesterdayAt(10)) // 80 × 0.1 = 8
        insertOutbox(productId = 101L, type = "VIEW", delta = 1L, occurredAt = todayBeforeCutoff()) // 20
        insertOutbox(productId = 102L, type = "SALES", delta = 2L, occurredAt = todayBeforeCutoff()) // 800

        val jobExecution = jobLauncherTestUtils.launchJob(jobParameters())

        val v1Status = jdbcTemplate.queryForObject("SELECT status FROM ranking_weight_config WHERE version = 'v1'", String::class.java)
        val v2Status = jdbcTemplate.queryForObject("SELECT status FROM ranking_weight_config WHERE version = 'v2'", String::class.java)
        assertAll(
            { assertThat(jobExecution.exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode) },
            { assertThat(redis.opsForZSet().score(v2AllKey, "101")).isEqualTo(28.0) },
            { assertThat(redis.opsForZSet().score(v2SnapshotKey, "101")).isEqualTo(28.0) },
            { assertThat(redis.opsForZSet().score(v2AllKey, "102")).isEqualTo(800.0) },
            { assertThat(redis.opsForValue().get(statusKey)).isEqualTo("DONE") },
            { assertThat(redis.opsForValue().get("ranking:weights:active")).isEqualTo("v2") },
            { assertThat(v2Status).isEqualTo("ACTIVE") },
            { assertThat(v1Status).isEqualTo("PREPARING") },
        )
    }

    @DisplayName("collector 이중 적재가 먼저 반영한 이벤트(dedup 키 존재)는 replay가 겹쳐도 중복 반영되지 않는다.")
    @Test
    fun skipsEventsAlreadyHandled_byCollectorDualWrite() {
        jobLauncherTestUtils.job = job
        insertConfig("v2", 20L, 80L, 400L, "PREPARING")
        val handledEventId = insertOutbox(productId = 101L, type = "LIKE", delta = 1L, occurredAt = todayBeforeCutoff())
        insertOutbox(productId = 101L, type = "VIEW", delta = 1L, occurredAt = todayBeforeCutoff())
        // collector가 이미 v2에 반영했다고 가정 - 버전별 dedup 키만 존재
        redis.opsForValue().set("ranking:handled:v2:$handledEventId", "1")

        jobLauncherTestUtils.launchJob(jobParameters())

        // LIKE(80)는 dedup으로 skip, VIEW(20)만 반영
        assertThat(redis.opsForZSet().score(v2AllKey, "101")).isEqualTo(20.0)
    }

    @DisplayName("컷오프 T 이후 이벤트와 그저께 이전 이벤트, 다른 토픽 이벤트는 replay 대상이 아니다.")
    @Test
    fun replaysOnlyEventsInRange() {
        jobLauncherTestUtils.job = job
        insertConfig("v2", 20L, 80L, 400L, "PREPARING")
        insertOutbox(productId = 101L, type = "VIEW", delta = 1L, occurredAt = cutoff.plusMinutes(5)) // 컷오프 이후 → collector 몫
        insertOutbox(productId = 101L, type = "VIEW", delta = 1L, occurredAt = yesterdayAt(10).minusDays(1)) // 그저께 → 버림
        insertOutbox(productId = 101L, type = "VIEW", delta = 1L, occurredAt = todayBeforeCutoff(), topic = "other.topic.v1")

        val jobExecution = jobLauncherTestUtils.launchJob(jobParameters())

        assertAll(
            { assertThat(jobExecution.exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode) },
            { assertThat(redis.hasKey(v2AllKey)).isFalse() },
        )
    }

    @DisplayName("다른 주체가 replay status를 선점(PROGRESS)하고 있으면, 재계산·flip 없이 정상 종료한다.")
    @Test
    fun skipsReplay_whenStatusHeldByOther() {
        jobLauncherTestUtils.job = job
        insertConfig("v2", 20L, 80L, 400L, "PREPARING")
        insertOutbox(productId = 101L, type = "VIEW", delta = 1L, occurredAt = todayBeforeCutoff())
        redis.opsForValue().set(statusKey, "PROGRESS")

        val jobExecution = jobLauncherTestUtils.launchJob(jobParameters())

        assertAll(
            { assertThat(jobExecution.exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode) },
            { assertThat(redis.hasKey(v2AllKey)).isFalse() },
            { assertThat(redis.opsForValue().get("ranking:weights:active")).isNull() },
        )
    }

    @DisplayName("가중치 설정이 없거나 PREPARING이 아니면 잡이 실패한다.")
    @Test
    fun fails_whenConfigMissingOrNotPreparing() {
        jobLauncherTestUtils.job = job

        val missing = jobLauncherTestUtils.launchJob(jobParameters())

        insertConfig("v2", 20L, 80L, 400L, "RETIRED")
        val retired = jobLauncherTestUtils.launchJob(jobParameters())

        assertAll(
            { assertThat(missing.exitStatus.exitCode).isEqualTo(ExitStatus.FAILED.exitCode) },
            { assertThat(retired.exitStatus.exitCode).isEqualTo(ExitStatus.FAILED.exitCode) },
        )
    }

    @DisplayName("최초 마이그레이션 - v1 자체도 같은 replay로 구축하고 active KV가 v1을 가리키게 된다.")
    @Test
    fun bootstrapsV1_asInitialMigration() {
        jobLauncherTestUtils.job = job
        insertConfig("v1", 10L, 50L, 500L, "PREPARING")
        insertOutbox(productId = 101L, type = "LIKE", delta = 1L, occurredAt = todayBeforeCutoff()) // 50

        val jobExecution = jobLauncherTestUtils.launchJob(jobParameters(version = "v1"))

        val todayCompact = today.format(DateTimeFormatter.BASIC_ISO_DATE)
        assertAll(
            { assertThat(jobExecution.exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode) },
            { assertThat(redis.opsForZSet().score("ranking:all:v1:$todayCompact", "101")).isEqualTo(50.0) },
            { assertThat(redis.opsForValue().get("ranking:weights:active")).isEqualTo("v1") },
        )
    }
}
