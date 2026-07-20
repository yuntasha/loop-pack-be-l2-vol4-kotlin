package com.loopers.infrastructure.ranking

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.ranking.RankingRolloverPort
import com.loopers.domain.ranking.RankingRolloverStatus
import com.loopers.utils.RedisCleanUp
import io.mockk.every
import io.mockk.spyk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@SpringBootTest
class RankingRolloverAdapterIntegrationTest @Autowired constructor(
    private val rankingRolloverPort: RankingRolloverPort,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) masterTemplate: RedisTemplate<*, *>,
    private val redisCleanUp: RedisCleanUp,
) {
    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    private val yesterday = LocalDate.of(2026, 7, 14)
    private val today = LocalDate.of(2026, 7, 15)
    private val fromKey = "ranking:snapshot:v1:20260714"
    private val toAllKey = "ranking:all:v1:20260715"
    private val toSnapshotKey = "ranking:snapshot:v1:20260715"
    private val statusKey = "ranking:rollover:status:v1:20260715"
    private val cursorKey = "ranking:rollover:cursor:v1:20260715"
    private val notifiedKey = "ranking:rollover:notified:v1:20260715"

    @AfterEach
    fun tearDown() {
        redisCleanUp.truncateAll()
    }

    private fun tryStart(): String = requireNotNull(rankingRolloverPort.tryStart("v1", today)) { "선점 실패" }

    @DisplayName("getStatus는 status 값(DONE/PROGRESS prefix/없음)을 3-way로 판별한다.")
    @Test
    fun resolvesStatusThreeWay() {
        assertThat(rankingRolloverPort.getStatus("v1", today)).isEqualTo(RankingRolloverStatus.NOT_STARTED)

        master.opsForValue().set(statusKey, "PROGRESS:some-owner-token")
        assertThat(rankingRolloverPort.getStatus("v1", today)).isEqualTo(RankingRolloverStatus.IN_PROGRESS)

        master.opsForValue().set(statusKey, "DONE")
        assertThat(rankingRolloverPort.getStatus("v1", today)).isEqualTo(RankingRolloverStatus.DONE)
    }

    @DisplayName("tryStart는 SET NX 선점 - 최초 1회만 소유자 토큰을 반환하고, PROGRESS:{token}과 TTL이 기록된다.")
    @Test
    fun tryStartActsAsDistributedLock() {
        val first = rankingRolloverPort.tryStart("v1", today)
        val second = rankingRolloverPort.tryStart("v1", today)

        assertAll(
            { assertThat(first).isNotNull() },
            { assertThat(second).isNull() },
            { assertThat(master.opsForValue().get(statusKey)).isEqualTo("PROGRESS:$first") },
            { assertThat(master.getExpire(statusKey, TimeUnit.SECONDS)).isGreaterThan(0L).isLessThanOrEqualTo(600L) },
        )
    }

    @DisplayName("carryOverSnapshot - ")
    @Nested
    inner class CarryOverSnapshot {

        @DisplayName("floor(×0.1)로 이월하고 0점은 소멸시키며, D+1 보드 TTL과 재개 커서를 페이지 Lua로 함께 기록한다.")
        @Test
        fun carriesOverWithFloorAndSkipZero() {
            master.opsForZSet().add(fromKey, "101", 1280.0) // → 128
            master.opsForZSet().add(fromKey, "102", 55.0) // → floor(5.5) = 5
            master.opsForZSet().add(fromKey, "103", 5.0) // → floor(0.5) = 0 → 소멸
            val token = tryStart()

            val completed = rankingRolloverPort.carryOverSnapshot("v1", fromDate = yesterday, toDate = today, ownerToken = token)

            val vanishedScore: Double? = master.opsForZSet().score(toAllKey, "103")
            assertAll(
                { assertThat(completed).isTrue() },
                { assertThat(master.opsForZSet().score(toAllKey, "101")).isEqualTo(128.0) },
                { assertThat(master.opsForZSet().score(toSnapshotKey, "101")).isEqualTo(128.0) },
                { assertThat(master.opsForZSet().score(toAllKey, "102")).isEqualTo(5.0) },
                { assertThat(vanishedScore).isNull() },
                { assertThat(master.getExpire(toAllKey, TimeUnit.SECONDS)).isGreaterThan(0L).isLessThanOrEqualTo(2 * 24 * 60 * 60L) },
                { assertThat(master.getExpire(toSnapshotKey, TimeUnit.SECONDS)).isGreaterThan(0L).isLessThanOrEqualTo(2 * 24 * 60 * 60L) },
                // 커서는 페이지 반영과 같은 Lua에서 원자 기록된다 - 처리한 멤버 수(carry=0 포함)만큼 전진
                { assertThat(master.opsForValue().get(cursorKey)).isEqualTo("3") },
                { assertThat(master.getExpire(cursorKey, TimeUnit.SECONDS)).isGreaterThan(0L).isLessThanOrEqualTo(2 * 24 * 60 * 60L) },
            )
        }

        @DisplayName("페이지 순회 시 PROGRESS TTL을 갱신한다(heartbeat) - 짧게 남은 TTL이 10분으로 늘어난다.")
        @Test
        fun refreshesProgressTtlAsHeartbeat() {
            master.opsForValue().set(statusKey, "PROGRESS:my-token", Duration.ofSeconds(30))
            master.opsForZSet().add(fromKey, "101", 1280.0)

            rankingRolloverPort.carryOverSnapshot("v1", fromDate = yesterday, toDate = today, ownerToken = "my-token")

            assertThat(master.getExpire(statusKey, TimeUnit.SECONDS)).isGreaterThan(30L).isLessThanOrEqualTo(600L)
        }

        @DisplayName("커서가 남아 있으면 그 오프셋부터 이어서 이월한다 - 이미 처리된 멤버는 다시 반영되지 않아 총 이월량이 정확히 ×0.1이다.")
        @Test
        fun resumesFromCursor_withoutDuplication() {
            // ZRANGE는 점수 오름차순: 103(200) → 102(500) → 101(1000)
            master.opsForZSet().add(fromKey, "103", 200.0)
            master.opsForZSet().add(fromKey, "102", 500.0)
            master.opsForZSet().add(fromKey, "101", 1000.0)
            // 이전 실행이 첫 멤버(103)까지 반영하고 크래시한 상황 재현: 이월분 반영 + 커서 1
            master.opsForZSet().add(toAllKey, "103", 20.0)
            master.opsForZSet().add(toSnapshotKey, "103", 20.0)
            master.opsForValue().set(cursorKey, "1")
            val token = tryStart()

            val completed = rankingRolloverPort.carryOverSnapshot("v1", fromDate = yesterday, toDate = today, ownerToken = token)

            assertAll(
                { assertThat(completed).isTrue() },
                // 103은 커서 앞이라 다시 만나지 않는다 - 두 배(40)가 아닌 정확히 20 유지
                { assertThat(master.opsForZSet().score(toAllKey, "103")).isEqualTo(20.0) },
                { assertThat(master.opsForZSet().score(toSnapshotKey, "103")).isEqualTo(20.0) },
                { assertThat(master.opsForZSet().score(toAllKey, "102")).isEqualTo(50.0) },
                { assertThat(master.opsForZSet().score(toAllKey, "101")).isEqualTo(100.0) },
                { assertThat(master.opsForValue().get(cursorKey)).isEqualTo("3") },
            )
        }

        @DisplayName("펜싱 - status의 소유자가 다르면(stall 중 다른 주체가 인수) 아무것도 쓰지 않고 false로 즉시 물러난다.")
        @Test
        fun stopsWithoutWriting_whenOwnershipLost() {
            master.opsForZSet().add(fromKey, "101", 1280.0)
            val token = tryStart()
            master.opsForValue().set(statusKey, "PROGRESS:new-owner") // 신 주체가 인수

            val completed = rankingRolloverPort.carryOverSnapshot("v1", fromDate = yesterday, toDate = today, ownerToken = token)

            assertAll(
                { assertThat(completed).isFalse() },
                { assertThat(master.hasKey(toAllKey)).isFalse() },
                { assertThat(master.hasKey(toSnapshotKey)).isFalse() },
                { assertThat(master.hasKey(cursorKey)).isFalse() },
                { assertThat(master.opsForValue().get(statusKey)).isEqualTo("PROGRESS:new-owner") },
            )
        }

        @DisplayName("마지막 페이지 반영 후 DONE 기록 전 크래시 - 커서가 이미 끝을 가리키므로 재진입은 아무것도 반영하지 않고 즉시 완주한다.")
        @Test
        fun completesImmediately_whenCursorAlreadyAtEnd() {
            master.opsForZSet().add(fromKey, "101", 1280.0)
            master.opsForZSet().add(fromKey, "102", 550.0)
            master.opsForValue().set(cursorKey, "2") // 모든 페이지 반영 완료 지점
            val token = tryStart()

            val completed = rankingRolloverPort.carryOverSnapshot("v1", fromDate = yesterday, toDate = today, ownerToken = token)
            val done = rankingRolloverPort.complete("v1", today, token)

            assertAll(
                { assertThat(completed).isTrue() },
                { assertThat(done).isTrue() },
                // 재진입의 ZRANGE(오프셋 2~)가 빈 결과라 중복 반영이 없다
                { assertThat(master.hasKey(toAllKey)).isFalse() },
                { assertThat(master.opsForValue().get(statusKey)).isEqualTo("DONE") },
                { assertThat(master.hasKey(cursorKey)).isFalse() },
            )
        }

        @DisplayName("응답 유실 가드 - 커서가 이미 지난 오프셋의 페이지 EVAL은 skip(0)되어 아무것도 쓰지 않는다.")
        @Test
        fun skipsPageEval_whenCursorAlreadyPassedOffset() {
            master.opsForValue().set(statusKey, "PROGRESS:my-token")
            master.opsForValue().set(cursorKey, "2") // 이전 시도가 실제로는 성공(커서 전진)했는데 응답만 유실된 상황

            val script = DefaultRedisScript(RankingRolloverAdapter.CARRY_OVER_PAGE_LUA, Long::class.java)
            val result = master.execute(
                script,
                listOf(statusKey, cursorKey, toAllKey, toSnapshotKey),
                "PROGRESS:my-token", "0", "2", "600", "172800", "172800", "101", "128",
            )

            assertAll(
                { assertThat(result).isEqualTo(0L) },
                { assertThat(master.hasKey(toAllKey)).isFalse() },
                { assertThat(master.hasKey(toSnapshotKey)).isFalse() },
                { assertThat(master.opsForValue().get(cursorKey)).isEqualTo("2") },
            )
        }
    }

    @DisplayName("complete - ")
    @Nested
    inner class Complete {

        @DisplayName("소유 토큰이 일치하면 status를 DONE(TTL 2일)으로 전이하고 커서를 삭제한다.")
        @Test
        fun completeOverwritesStatusToDoneAndDeletesCursor() {
            val token = tryStart()
            master.opsForValue().set(cursorKey, "3")

            val done = rankingRolloverPort.complete("v1", today, token)

            assertAll(
                { assertThat(done).isTrue() },
                { assertThat(master.opsForValue().get(statusKey)).isEqualTo("DONE") },
                { assertThat(master.getExpire(statusKey, TimeUnit.SECONDS)).isGreaterThan(600L).isLessThanOrEqualTo(2 * 24 * 60 * 60L) },
                { assertThat(master.hasKey(cursorKey)).isFalse() },
            )
        }

        @DisplayName("소유 토큰이 다르면(소유권 상실) DONE을 기록하지 않고 false를 반환한다.")
        @Test
        fun doesNotComplete_whenNotOwner() {
            tryStart()
            master.opsForValue().set(statusKey, "PROGRESS:new-owner")
            master.opsForValue().set(cursorKey, "3")

            val done = rankingRolloverPort.complete("v1", today, "stale-token")

            assertAll(
                { assertThat(done).isFalse() },
                { assertThat(master.opsForValue().get(statusKey)).isEqualTo("PROGRESS:new-owner") },
                { assertThat(master.opsForValue().get(cursorKey)).isEqualTo("3") },
            )
        }
    }

    @DisplayName("리트라이 소진 포기 경로 - ")
    @Nested
    inner class AbandonPath {

        /** ZRANGE가 계속 연결 장애를 던지는 어댑터 - 실제 Redis에 붙은 템플릿을 spyk로 감싸 읽기만 실패시킨다. */
        private fun failingAdapter(): RankingRolloverAdapter {
            val failingTemplate = spyk(master)
            every { failingTemplate.opsForZSet() } throws RedisConnectionFailureException("계속 순단")
            return RankingRolloverAdapter(failingTemplate, RedisTransientRetry(sleeper = {}))
        }

        @DisplayName("연결 장애가 지속되면 내 소유 status를 조건부 삭제하고(즉시 재선점 가능) 커서는 남긴 채 예외를 던진다.")
        @Test
        fun releasesOwnStatusAndKeepsCursor_whenRetriesExhausted() {
            master.opsForValue().set(statusKey, "PROGRESS:my-token")
            master.opsForValue().set(cursorKey, "2")

            assertThatThrownBy {
                failingAdapter().carryOverSnapshot("v1", fromDate = yesterday, toDate = today, ownerToken = "my-token")
            }.isInstanceOf(RedisConnectionFailureException::class.java)

            assertAll(
                { assertThat(master.hasKey(statusKey)).isFalse() },
                { assertThat(master.opsForValue().get(cursorKey)).isEqualTo("2") },
            )
        }

        @DisplayName("포기 시에도 타 소유자의 status는 지우지 않는다 - 새 주체의 진행을 방해하지 않는다.")
        @Test
        fun keepsOtherOwnersStatus_whenRetriesExhausted() {
            master.opsForValue().set(statusKey, "PROGRESS:new-owner")
            master.opsForValue().set(cursorKey, "2")

            assertThatThrownBy {
                failingAdapter().carryOverSnapshot("v1", fromDate = yesterday, toDate = today, ownerToken = "stale-token")
            }.isInstanceOf(RedisConnectionFailureException::class.java)

            assertThat(master.opsForValue().get(statusKey)).isEqualTo("PROGRESS:new-owner")
        }
    }

    @DisplayName("tryMarkNotified는 SET NX 가드 - 최초 1회만 true를 반환해 WARN 로그 중복을 막는다.")
    @Test
    fun notifiedGuardAllowsOnlyFirst() {
        val first = rankingRolloverPort.tryMarkNotified("v1", today)
        val second = rankingRolloverPort.tryMarkNotified("v1", today)

        assertAll(
            { assertThat(first).isTrue() },
            { assertThat(second).isFalse() },
            { assertThat(master.getExpire(notifiedKey, TimeUnit.SECONDS)).isGreaterThan(0L).isLessThanOrEqualTo(24 * 60 * 60L) },
        )
    }
}
