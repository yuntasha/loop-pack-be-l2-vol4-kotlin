package com.loopers.batch.job.ranking.step

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.batch.job.ranking.RankingRolloverJobConfig
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
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.floor

/**
 * boards KV(ranking:weights:boards)의 모든 적재 버전에 대해 snapshot:{v}:{D}를 페이징 순회하며
 * carry = floor(score × 0.1)을 D+1 보드(all/snapshot)에 반영한다.
 * carry가 0이면 add를 생략해 미미한 점수는 자연 소멸시키고 ZSET 크기를 억제한다.
 *
 * 상태 키(ranking:rollover:status:{v}:{D+1})는 버전별 `PROGRESS:{ownerToken}` SET NX로 분산 락을 겸하며
 * commerce-api의 장애 복구 트리거와 같은 키를 공유한다 — 키 규약·status 값 포맷·Lua 스크립트는
 * commerce-api RankingRolloverAdapter와 상수 계약으로 동일하게 유지한다.
 *
 * 장애 내성 구조(세 겹):
 * - 페이지 반영(ZINCRBY×N) + 커서 갱신 + heartbeat를 하나의 Lua로 원자화 → 재실행이 커서부터 이어져 중복 없음
 * - 소유자 토큰 펜싱 → stall로 PROGRESS 만료 후 이중 실행 시 구 주체의 쓰기 차단
 * - 연결·타임아웃 순단은 페이지 연산 단위 리트라이, 소진 시 status만 정리(best-effort)하고 커서는 남긴 채 배치 실패로 종료
 */
@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = RankingRolloverJobConfig.JOB_NAME)
@Component
class RankingRolloverTasklet(
    @param:Value("#{jobParameters['requestDate']}") private val requestDate: String?,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) masterTemplate: RedisTemplate<*, *>,
    private val objectMapper: ObjectMapper,
) : Tasklet {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    private val retry = RedisTransientRetry()
    private val pageScript = DefaultRedisScript(CARRY_OVER_PAGE_LUA, Long::class.java)
    private val completeScript = DefaultRedisScript(COMPLETE_IF_OWNER_LUA, Long::class.java)
    private val releaseScript = DefaultRedisScript(RELEASE_IF_OWNER_LUA, Long::class.java)

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val sourceDate = requestDate?.let { LocalDate.parse(it) } ?: LocalDate.now(ZONE)
        val targetDate = sourceDate.plusDays(1)

        boardVersions().forEach { version ->
            val statusKey = statusKey(version, targetDate)
            val ownerValue = "$STATUS_PROGRESS:${UUID.randomUUID()}"
            val started = master.opsForValue().setIfAbsent(statusKey, ownerValue, PROGRESS_TTL) == true
            if (!started) {
                log.warn("이월 상태 선점 실패 - 다른 주체가 실행 중이거나 이미 완료됐으므로 이 버전은 건너뛴다. statusKey={}", statusKey)
                return@forEach
            }

            // false = 소유권 상실(stall 후 다른 주체가 인수) - 새 소유자가 진행 중이므로 완료 처리 없이 물러난다
            if (carryOver(version, sourceDate, targetDate, statusKey, ownerValue)) {
                completeIfOwner(version, targetDate, statusKey, ownerValue)
            }
        }
        return RepeatStatus.FINISHED
    }

    /** 적재 대상 버전 목록. KV 미존재/장애 시 기본 v1 — 이월이 설정 조회 장애로 멈추면 안 된다. */
    private fun boardVersions(): List<String> = runCatching {
        val json = master.opsForValue().get(BOARDS_KEY) ?: return listOf(DEFAULT_VERSION)
        objectMapper.readTree(json).mapNotNull { it.get("version")?.asText() }.ifEmpty { listOf(DEFAULT_VERSION) }
    }.getOrElse {
        log.warn("가중치 boards KV 조회 실패 - 기본 {}만 이월한다.", DEFAULT_VERSION, it)
        listOf(DEFAULT_VERSION)
    }

    private fun carryOver(
        version: String,
        sourceDate: LocalDate,
        targetDate: LocalDate,
        statusKey: String,
        ownerValue: String,
    ): Boolean {
        val fromKey = "ranking:snapshot:$version:${sourceDate.format(DATE_FORMAT)}"
        val toAllKey = "ranking:all:$version:${targetDate.format(DATE_FORMAT)}"
        val toSnapshotKey = "ranking:snapshot:$version:${targetDate.format(DATE_FORMAT)}"
        val cursorKey = cursorKey(version, targetDate)
        val pageKeys = listOf(statusKey, cursorKey, toAllKey, toSnapshotKey)

        try {
            var offset = readCursor(cursorKey)
            var carried = 0L
            while (true) {
                val tuples = retry.execute("snapshot ZRANGE") {
                    master.opsForZSet().rangeWithScores(fromKey, offset, offset + PAGE_SIZE - 1)
                }?.toList().orEmpty()
                if (tuples.isEmpty()) break

                val args = pageArgs(ownerValue, offset, nextCursor = offset + tuples.size, tuples)
                val result = retry.execute("carry-over page EVAL") {
                    master.execute(pageScript, pageKeys, *args.toTypedArray())
                }
                when (result) {
                    PAGE_OWNERSHIP_LOST -> {
                        log.warn("이월 소유권 상실 - 다른 주체가 인수해 진행 중이므로 즉시 중단한다. statusKey={}, offset={}", statusKey, offset)
                        return false
                    }
                    // 응답 유실 후 리트라이 등으로 커서가 이미 이 페이지를 지났다 - 커서 기준으로 재동기화해 중복을 막는다
                    PAGE_ALREADY_APPLIED -> offset = readCursor(cursorKey)
                    else -> {
                        carried += (args.size - PAGE_FIXED_ARGS) / 2
                        offset += tuples.size
                        if (tuples.size < PAGE_SIZE) break
                    }
                }
            }
            log.info("랭킹 이월 완료. from={}, to={}, carriedMembers={}", fromKey, toAllKey, carried)
            return true
        } catch (e: Exception) {
            if (RedisTransientRetry.isTransient(e)) abandon(statusKey, ownerValue, e)
            throw e
        }
    }

    private fun completeIfOwner(version: String, targetDate: LocalDate, statusKey: String, ownerValue: String) {
        val keys = listOf(statusKey, cursorKey(version, targetDate))
        val done = master.execute(completeScript, keys, ownerValue, STATUS_DONE, DONE_TTL.seconds.toString()) == 1L
        if (!done) {
            log.warn("이월 완료 기록 실패 - 소유권이 이미 다른 주체로 넘어갔다. statusKey={}", statusKey)
        }
    }

    /**
     * 리트라이 소진 시 포기 경로 - 소유 status만 조건부 삭제해 다음 주체가 TTL 만료(≤10분)를 기다리지 않고
     * 즉시 재선점하게 한다. Redis가 아직 죽어 있어 삭제도 실패하면 무시(TTL 만료가 안전망).
     * 커서는 삭제하지 않는다 - 다음 주체의 재개 지점이다.
     */
    private fun abandon(statusKey: String, ownerValue: String, cause: Exception) {
        runCatching { master.execute(releaseScript, listOf(statusKey), ownerValue) }
            .onFailure { log.warn("포기 시 status 조건부 삭제 실패 - PROGRESS TTL 만료가 안전망으로 동작한다. statusKey={}", statusKey, it) }
        log.error("이월 리트라이 소진으로 포기 - 커서를 남겨 다음 주체가 이어서 실행한다. statusKey={}", statusKey, cause)
    }

    private fun readCursor(cursorKey: String): Long =
        retry.execute("cursor GET") { master.opsForValue().get(cursorKey) }?.toLongOrNull() ?: 0L

    private fun pageArgs(
        ownerValue: String,
        offset: Long,
        nextCursor: Long,
        tuples: List<TypedTuple<String>>,
    ): List<String> = buildList {
        add(ownerValue)
        add(offset.toString())
        add(nextCursor.toString())
        add(PROGRESS_TTL.seconds.toString())
        add(CURSOR_TTL.seconds.toString())
        add(ZSET_TTL.seconds.toString())
        tuples.forEach { tuple ->
            val member = tuple.value ?: return@forEach
            val carry = floor((tuple.score ?: 0.0) * CARRY_OVER_FACTOR).toLong()
            if (carry == 0L) return@forEach
            add(member)
            add(carry.toString())
        }
    }

    private fun statusKey(version: String, targetDate: LocalDate): String =
        "ranking:rollover:status:$version:${targetDate.format(DATE_FORMAT)}"

    private fun cursorKey(version: String, targetDate: LocalDate): String =
        "ranking:rollover:cursor:$version:${targetDate.format(DATE_FORMAT)}"

    companion object {
        private val ZONE = ZoneId.of("Asia/Seoul")
        private val DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
        private const val BOARDS_KEY = "ranking:weights:boards"
        private const val DEFAULT_VERSION = "v1"
        private const val STATUS_PROGRESS = "PROGRESS"
        private const val STATUS_DONE = "DONE"
        private val PROGRESS_TTL = Duration.ofMinutes(10)
        private val DONE_TTL = Duration.ofDays(2)
        private val ZSET_TTL = Duration.ofDays(2)
        private val CURSOR_TTL = Duration.ofDays(2)
        private const val CARRY_OVER_FACTOR = 0.1
        private const val PAGE_SIZE = 1000
        private const val PAGE_FIXED_ARGS = 6

        private const val PAGE_OWNERSHIP_LOST = -1L
        private const val PAGE_ALREADY_APPLIED = 0L

        /**
         * 페이지 반영 Lua - commerce-api RankingRolloverAdapter와 동일한 상수 계약.
         * KEYS[1] = status key, KEYS[2] = cursor key, KEYS[3] = ranking:all:{v}:{D+1}, KEYS[4] = ranking:snapshot:{v}:{D+1}
         * ARGV[1] = 소유자 status 값 (PROGRESS:{ownerToken})
         * ARGV[2] = 이 페이지의 오프셋, ARGV[3] = 다음 커서
         * ARGV[4] = progress ttl, ARGV[5] = cursor ttl, ARGV[6] = zset ttl (초)
         * ARGV[7..] = (member, carry) 반복 - carry=0 멤버는 클라이언트에서 제외
         *
         * 반환: 1 = 반영, -1 = 소유권 상실(펜싱), 0 = 커서가 이미 이 페이지를 지남(응답 유실 재시도 가드)
         */
        internal const val CARRY_OVER_PAGE_LUA = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return -1
            end
            if tonumber(redis.call('GET', KEYS[2]) or '0') ~= tonumber(ARGV[2]) then
                return 0
            end
            for i = 7, #ARGV, 2 do
                redis.call('ZINCRBY', KEYS[3], ARGV[i + 1], ARGV[i])
                redis.call('ZINCRBY', KEYS[4], ARGV[i + 1], ARGV[i])
            end
            redis.call('EXPIRE', KEYS[3], ARGV[6])
            redis.call('EXPIRE', KEYS[4], ARGV[6])
            redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[5])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
        """

        /**
         * 완료 조건부 Lua - GET status == 내 소유 값일 때만 DONE 전이 + 커서 삭제.
         * KEYS[1] = status key, KEYS[2] = cursor key
         * ARGV[1] = 소유자 status 값, ARGV[2] = DONE, ARGV[3] = done ttl (초)
         */
        internal const val COMPLETE_IF_OWNER_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
                redis.call('DEL', KEYS[2])
                return 1
            end
            return 0
        """

        /**
         * 포기 조건부 Lua - GET status == 내 소유 값일 때만 삭제 (타 소유자의 status는 건드리지 않는다).
         * KEYS[1] = status key, ARGV[1] = 소유자 status 값
         */
        internal const val RELEASE_IF_OWNER_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            return 0
        """
    }
}
