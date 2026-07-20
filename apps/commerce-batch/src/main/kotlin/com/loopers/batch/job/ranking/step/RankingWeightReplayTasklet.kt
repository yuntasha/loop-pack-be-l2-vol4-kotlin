package com.loopers.batch.job.ranking.step

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.batch.job.ranking.RankingWeightReplayJobConfig
import com.loopers.config.redis.RedisConfig
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * outbox를 id 커서로 페이징 순회하며 occurredAt ∈ [어제 00:00, 컷오프 T) 이벤트를
 * 대상 버전 가중치로 재계산해 오늘 v{n} 보드(all/snapshot)에 반영한다.
 * - 어제분은 ×0.1 이월분으로(저장 가중치가 ×10 스케일이라 항상 정수), 오늘분은 원 점수로 적재한다.
 * - collector 이중 적재와 동일한 Lua(dedup SETNX + ZINCRBY + EXPIRE)를 쓰므로 겹쳐도 버전당 한 번만 반영된다.
 * - outbox status(CREATED/PUBLISHED)는 무관 — 미발행분을 먼저 반영해도 나중에 collector가 처리할 때 dedup이 막는다.
 *
 * 완료 시 replay status를 DONE으로 덮어쓰고, 대상 버전을 ACTIVE로 전이(기존 ACTIVE는 PREPARING 강등) +
 * ranking:weights:active KV를 flip한다. 이후 조회는 즉시 신 버전 보드를 서빙한다.
 */
@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = RankingWeightReplayJobConfig.JOB_NAME)
@Component
class RankingWeightReplayTasklet(
    @param:Value("#{jobParameters['version']}") private val version: String?,
    @param:Value("#{jobParameters['cutoffAt']}") private val cutoffAt: String?,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) masterTemplate: RedisTemplate<*, *>,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : Tasklet {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    private val incrementScript = DefaultRedisScript(INCREMENT_WITH_DEDUP_LUA, Long::class.java)

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val targetVersion = requireNotNull(version) { "jobParameters['version']은 필수다. 예: v2" }
        val cutoffParam = requireNotNull(cutoffAt) { "jobParameters['cutoffAt']은 필수다. 예: 2026-07-16T14:30:00+09:00" }
        val cutoff = ZonedDateTime.parse(cutoffParam)
        require(!cutoff.isAfter(ZonedDateTime.now())) { "컷오프가 미래면 outbox에 대상 이벤트가 아직 없다. cutoffAt=$cutoff" }

        val weights = loadWeights(targetVersion)
        val today = cutoff.withZoneSameInstant(ZONE).toLocalDate()
        val yesterday = today.minusDays(1)

        val statusKey = "ranking:replay:status:$targetVersion"
        val started = master.opsForValue().setIfAbsent(statusKey, STATUS_PROGRESS, PROGRESS_TTL) == true
        if (!started) {
            log.warn("replay 상태 선점 실패 - 다른 주체가 실행 중이거나 이미 완료됐으므로 종료한다. statusKey={}", statusKey)
            return RepeatStatus.FINISHED
        }

        replay(targetVersion, weights, yesterday, today, cutoff, statusKey)

        master.opsForValue().set(statusKey, STATUS_DONE, DONE_TTL)
        flip(targetVersion)
        return RepeatStatus.FINISHED
    }

    private fun loadWeights(targetVersion: String): Map<String, Long> {
        val row = jdbcTemplate.queryForList(
            "SELECT view_weight, like_weight, order_weight, status FROM ranking_weight_config WHERE version = ?",
            targetVersion,
        ).firstOrNull() ?: throw IllegalArgumentException("가중치 설정이 없다. 먼저 admin API로 등록해야 한다. version=$targetVersion")

        val status = row["status"] as String
        require(status == "PREPARING") { "PREPARING 상태만 replay할 수 있다. version=$targetVersion, status=$status" }

        // payload의 메트릭 타입(VIEW/LIKE/SALES) 기준 가중치 맵 (×10 저장 스케일 그대로)
        return mapOf(
            "VIEW" to (row["view_weight"] as Number).toLong(),
            "LIKE" to (row["like_weight"] as Number).toLong(),
            "SALES" to (row["order_weight"] as Number).toLong(),
        )
    }

    private fun replay(
        targetVersion: String,
        weights: Map<String, Long>,
        yesterday: LocalDate,
        today: LocalDate,
        cutoff: ZonedDateTime,
        statusKey: String,
    ) {
        val allKey = "ranking:all:$targetVersion:${today.format(DATE_FORMAT)}"
        val snapshotKey = "ranking:snapshot:$targetVersion:${today.format(DATE_FORMAT)}"
        // occurred_at은 NORMALIZE_UTC로 저장되므로 UTC LocalDateTime으로 바인딩한다
        val fromUtc = yesterday.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        val toUtc = cutoff.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

        var cursor = 0L
        var replayed = 0L
        var skipped = 0L
        while (true) {
            val rows = jdbcTemplate.queryForList(
                """
                SELECT id, payload FROM outbox
                WHERE topic = ? AND occurred_at >= ? AND occurred_at < ? AND id > ?
                ORDER BY id LIMIT $PAGE_SIZE
                """.trimIndent(),
                PRODUCT_METRIC_TOPIC,
                fromUtc,
                toUtc,
                cursor,
            )
            if (rows.isEmpty()) break
            cursor = (rows.last()["id"] as Number).toLong()

            rows.forEach { row ->
                val applied = runCatching {
                    reflectPayload(targetVersion, weights, yesterday, allKey, snapshotKey, row["payload"] as String)
                }.getOrElse { e ->
                    log.error("payload 반영 실패 - 해당 이벤트를 건너뛴다. id={}", row["id"], e)
                    false
                }
                if (applied) replayed++ else skipped++
            }

            // heartbeat - 살아있는 한 총 소요가 PROGRESS TTL을 넘어도 상태가 유지되고, 크래시하면 갱신이 멈춰 자연 만료된다
            master.expire(statusKey, PROGRESS_TTL)

            if (rows.size < PAGE_SIZE) break
        }

        master.expire(allKey, ZSET_TTL)
        master.expire(snapshotKey, ZSET_TTL)
        log.info(
            "가중치 replay 완료. version={}, board={}, replayed={}, skipped(dedup/unknown)={}",
            targetVersion,
            allKey,
            replayed,
            skipped,
        )
    }

    /** @return true = 실제 반영 / false = dedup skip 또는 알 수 없는 타입. */
    private fun reflectPayload(
        targetVersion: String,
        weights: Map<String, Long>,
        yesterday: LocalDate,
        allKey: String,
        snapshotKey: String,
        payloadJson: String,
    ): Boolean {
        val payload = objectMapper.readValue(payloadJson, ReplayPayload::class.java)
        val weight = weights[payload.type] ?: run {
            log.warn("알 수 없는 메트릭 타입이라 replay를 건너뛴다. type={}, eventId={}", payload.type, payload.eventId)
            return false
        }

        val occurredDate = payload.occurredAt.withZoneSameInstant(ZONE).toLocalDate()
        val score = weight * payload.delta
        // 어제분은 오늘 보드에 ×0.1 이월분으로 반영한다 (저장 가중치가 ×10 스케일이라 항상 정수)
        val effectiveScore = if (occurredDate == yesterday) score / CARRY_OVER_DIVISOR else score
        if (effectiveScore == 0L) return false

        val keys = listOf("ranking:handled:$targetVersion:${payload.eventId}", allKey, snapshotKey)
        val args = arrayOf(
            payload.productId.toString(),
            DEDUP_TTL_SECONDS.toString(),
            ZSET_TTL_SECONDS.toString(),
            effectiveScore.toString(),
            effectiveScore.toString(),
        )
        return master.execute(incrementScript, keys, *args) == 1L
    }

    /** 대상 버전을 ACTIVE로 전이하고(기존 ACTIVE는 PREPARING 강등) 활성 버전 포인터를 flip한다. */
    private fun flip(targetVersion: String) {
        jdbcTemplate.update(
            "UPDATE ranking_weight_config SET status = 'PREPARING' WHERE status = 'ACTIVE' AND version != ?",
            targetVersion,
        )
        jdbcTemplate.update(
            "UPDATE ranking_weight_config SET status = 'ACTIVE', activated_at = ? WHERE version = ?",
            ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime(),
            targetVersion,
        )
        master.opsForValue().set(ACTIVE_KEY, targetVersion)
        log.info("활성 가중치 버전 flip 완료. active={}", targetVersion)
    }

    private data class ReplayPayload(
        val eventId: String,
        val productId: Long,
        val type: String,
        val delta: Long,
        val occurredAt: ZonedDateTime,
    )

    companion object {
        private val ZONE = ZoneId.of("Asia/Seoul")
        private val DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
        private const val PRODUCT_METRIC_TOPIC = "product.metric.v1"
        private const val ACTIVE_KEY = "ranking:weights:active"
        private const val STATUS_PROGRESS = "PROGRESS"
        private const val STATUS_DONE = "DONE"
        private val PROGRESS_TTL = Duration.ofMinutes(10)
        private val DONE_TTL = Duration.ofDays(2)
        private val ZSET_TTL = Duration.ofDays(2)
        private const val ZSET_TTL_SECONDS = 2 * 24 * 60 * 60L
        private const val DEDUP_TTL_SECONDS = 24 * 60 * 60L
        private const val CARRY_OVER_DIVISOR = 10L
        private const val PAGE_SIZE = 1000

        /**
         * KEYS[1] = dedup key / KEYS[2..N] = zset keys / ARGV[1] = member / ARGV[2] = dedup ttl /
         * ARGV[3] = zset ttl / ARGV[4..] = KEYS[i+2]에 대응하는 score delta. collector와 동일한 스크립트다.
         */
        private const val INCREMENT_WITH_DEDUP_LUA = """
            if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[2]) then
                for i = 2, #KEYS do
                    redis.call('ZINCRBY', KEYS[i], ARGV[i + 2], ARGV[1])
                    redis.call('EXPIRE', KEYS[i], ARGV[3])
                end
                return 1
            else
                return 0
            end
        """
    }
}
