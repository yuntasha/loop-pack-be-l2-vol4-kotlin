package com.loopers.infrastructure.ranking

import com.loopers.config.redis.RedisConfig
import com.loopers.utils.RedisCleanUp
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@SpringBootTest
class RankingStatusMetricsExporterIntegrationTest @Autowired constructor(
    private val exporter: RankingStatusMetricsExporter,
    private val meterRegistry: MeterRegistry,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) masterTemplate: RedisTemplate<*, *>,
    private val redisCleanUp: RedisCleanUp,
) {
    @Suppress("UNCHECKED_CAST")
    private val redis = masterTemplate as RedisTemplate<String, String>

    private val today = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE)

    @AfterEach
    fun tearDown() {
        redisCleanUp.truncateAll()
    }

    private fun gaugeValue(name: String, version: String): Double? =
        meterRegistry.find(name).tag("version", version).gauge()?.value()

    @DisplayName("KV가 하나도 없으면 기본 v1로 노출된다 - rollover/replay 0(없음), active 1.")
    @Test
    fun exportsDefaultV1_whenNoKv() {
        exporter.refresh()

        assertAll(
            { assertThat(gaugeValue("ranking.rollover.status", "v1")).isEqualTo(0.0) },
            { assertThat(gaugeValue("ranking.replay.status", "v1")).isEqualTo(0.0) },
            { assertThat(gaugeValue("ranking.weights.active", "v1")).isEqualTo(1.0) },
        )
    }

    @DisplayName("전환 중 상태가 버전 태그별 게이지로 노출된다 - v1 이월 DONE·서빙 중(1), v2 replay PROGRESS·비활성(0).")
    @Test
    fun exportsPerVersionStatus_duringTransition() {
        redis.opsForValue().set(
            "ranking:weights:boards",
            """[{"version":"v1","weights":{"VIEW":10,"LIKE":50,"ORDER":500}},{"version":"v2","weights":{"VIEW":20,"LIKE":80,"ORDER":400}}]""",
        )
        redis.opsForValue().set("ranking:weights:active", "v1")
        redis.opsForValue().set("ranking:rollover:status:v1:$today", "DONE")
        redis.opsForValue().set("ranking:replay:status:v2", "PROGRESS")

        exporter.refresh()

        assertAll(
            { assertThat(gaugeValue("ranking.rollover.status", "v1")).isEqualTo(2.0) },
            { assertThat(gaugeValue("ranking.rollover.status", "v2")).isEqualTo(0.0) },
            { assertThat(gaugeValue("ranking.replay.status", "v2")).isEqualTo(1.0) },
            { assertThat(gaugeValue("ranking.weights.active", "v1")).isEqualTo(1.0) },
            { assertThat(gaugeValue("ranking.weights.active", "v2")).isEqualTo(0.0) },
        )
    }

    @DisplayName("이월 status가 PROGRESS:{ownerToken} 포맷이어도 prefix로 판정해 1(PROGRESS)로 노출된다.")
    @Test
    fun exportsProgress_whenStatusHasOwnerToken() {
        redis.opsForValue().set("ranking:rollover:status:v1:$today", "PROGRESS:0f8a1c2e-owner-token")

        exporter.refresh()

        assertThat(gaugeValue("ranking.rollover.status", "v1")).isEqualTo(1.0)
    }

    @DisplayName("flip 후 재갱신하면 active 게이지가 v2=1/v1=0으로 뒤집힌다 - 시계열로 전환 시점을 추적할 수 있다.")
    @Test
    fun flipsActiveGauge_whenActiveVersionChanges() {
        redis.opsForValue().set(
            "ranking:weights:boards",
            """[{"version":"v1","weights":{"VIEW":10,"LIKE":50,"ORDER":500}},{"version":"v2","weights":{"VIEW":20,"LIKE":80,"ORDER":400}}]""",
        )
        redis.opsForValue().set("ranking:weights:active", "v1")
        exporter.refresh()
        val beforeFlip = gaugeValue("ranking.weights.active", "v2")

        redis.opsForValue().set("ranking:weights:active", "v2")
        redis.opsForValue().set("ranking:replay:status:v2", "DONE")
        exporter.refresh()

        assertAll(
            { assertThat(beforeFlip).isEqualTo(0.0) },
            { assertThat(gaugeValue("ranking.weights.active", "v2")).isEqualTo(1.0) },
            { assertThat(gaugeValue("ranking.weights.active", "v1")).isEqualTo(0.0) },
            { assertThat(gaugeValue("ranking.replay.status", "v2")).isEqualTo(2.0) },
        )
    }

    @DisplayName("boards KV에서 빠진(은퇴한) 버전의 게이지는 다음 갱신에서 제거된다.")
    @Test
    fun removesGauge_whenVersionRetired() {
        redis.opsForValue().set(
            "ranking:weights:boards",
            """[{"version":"v1","weights":{"VIEW":10,"LIKE":50,"ORDER":500}},{"version":"v2","weights":{"VIEW":20,"LIKE":80,"ORDER":400}}]""",
        )
        redis.opsForValue().set("ranking:weights:active", "v2")
        exporter.refresh()
        val beforeRetire = gaugeValue("ranking.weights.active", "v1")

        redis.opsForValue().set("ranking:weights:boards", """[{"version":"v2","weights":{"VIEW":20,"LIKE":80,"ORDER":400}}]""")
        exporter.refresh()

        assertAll(
            { assertThat(beforeRetire).isEqualTo(0.0) },
            { assertThat(gaugeValue("ranking.weights.active", "v1")).isNull() },
            { assertThat(gaugeValue("ranking.weights.active", "v2")).isEqualTo(1.0) },
        )
    }
}
