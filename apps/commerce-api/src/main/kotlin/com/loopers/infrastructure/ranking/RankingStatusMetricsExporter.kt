package com.loopers.infrastructure.ranking

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 랭킹 운영 상태를 Prometheus 시계열로 노출하는 관측 어댑터. 주기 폴링으로 게이지를 갱신한다.
 *
 * - ranking.rollover.status{version}  : 오늘 보드의 이월 상태 (0=NOT_STARTED, 1=PROGRESS, 2=DONE).
 *   자정 이후에도 0/1이면 이월 지연 - 폴백 서빙 중이라는 뜻이라 알람 조건으로 쓸 수 있다.
 * - ranking.replay.status{version}    : 가중치 재계산 상태 (0=없음, 1=PROGRESS, 2=DONE). 전환 진행을 추적한다.
 * - ranking.weights.active{version}   : 활성 버전이면 1, 아니면 0. 시계열로 보면 flip/롤백 시점이 그대로 드러난다.
 *
 * 조회 실패 시 게이지를 갱신하지 않고 WARN만 남긴다 - 관측이 서비스에 영향을 주면 안 된다.
 */
@Component
class RankingStatusMetricsExporter(
    replicaTemplate: RedisTemplate<*, *>,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    private val replica = replicaTemplate as RedisTemplate<String, String>

    private val rolloverGauge = MultiGauge.builder("ranking.rollover.status")
        .description("오늘 보드의 버전별 이월 상태 (0=NOT_STARTED, 1=PROGRESS, 2=DONE)")
        .register(meterRegistry)

    private val replayGauge = MultiGauge.builder("ranking.replay.status")
        .description("버전별 가중치 재계산(replay) 상태 (0=없음, 1=PROGRESS, 2=DONE)")
        .register(meterRegistry)

    private val activeGauge = MultiGauge.builder("ranking.weights.active")
        .description("버전별 활성(서빙) 여부 (1=active) - 시계열로 flip/롤백 시점을 추적한다")
        .register(meterRegistry)

    @Scheduled(fixedDelayString = "\${ranking.metrics.interval-ms:15000}")
    fun refresh() {
        runCatching {
            val activeVersion = replica.opsForValue().get(ACTIVE_KEY) ?: DEFAULT_VERSION
            val versions = (boardVersions() + activeVersion).distinct()
            val today = LocalDate.now(ZONE)

            rolloverGauge.register(
                versions.map { version ->
                    MultiGauge.Row.of(Tags.of(TAG_VERSION, version), statusValue(rolloverStatus(version, today)))
                },
                true,
            )
            replayGauge.register(
                versions.map { version ->
                    val replayStatus = replica.opsForValue().get("ranking:replay:status:$version")
                    MultiGauge.Row.of(Tags.of(TAG_VERSION, version), statusValue(replayStatus))
                },
                true,
            )
            activeGauge.register(
                versions.map { version ->
                    MultiGauge.Row.of(Tags.of(TAG_VERSION, version), if (version == activeVersion) 1 else 0)
                },
                true,
            )
        }.onFailure {
            log.warn("랭킹 상태 메트릭 갱신 실패 - 이전 게이지 값을 유지한다.", it)
        }
    }

    private fun boardVersions(): List<String> {
        val json = replica.opsForValue().get(BOARDS_KEY) ?: return emptyList()
        return objectMapper.readTree(json).mapNotNull { it.get("version")?.asText() }
    }

    private fun rolloverStatus(version: String, today: LocalDate): String? =
        replica.opsForValue().get("ranking:rollover:status:$version:${today.format(DateTimeFormatter.BASIC_ISO_DATE)}")

    // 이월 status는 PROGRESS:{ownerToken} 포맷이라 prefix로 판정한다 (replay의 순수 PROGRESS도 포함)
    private fun statusValue(status: String?): Int = when {
        status == STATUS_DONE -> 2
        status?.startsWith(STATUS_PROGRESS) == true -> 1
        else -> 0
    }

    companion object {
        private val ZONE = ZoneId.of("Asia/Seoul")
        private const val ACTIVE_KEY = "ranking:weights:active"
        private const val BOARDS_KEY = "ranking:weights:boards"
        private const val DEFAULT_VERSION = "v1"
        private const val TAG_VERSION = "version"
        private const val STATUS_PROGRESS = "PROGRESS"
        private const val STATUS_DONE = "DONE"
    }
}
