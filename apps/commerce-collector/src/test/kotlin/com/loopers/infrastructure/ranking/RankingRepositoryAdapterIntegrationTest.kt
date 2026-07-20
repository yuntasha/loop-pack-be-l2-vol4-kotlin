package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.BoardScore
import com.loopers.domain.ranking.RankingBoard
import com.loopers.domain.ranking.RankingRepositoryPort
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate
import java.time.LocalDate

@SpringBootTest
class RankingRepositoryAdapterIntegrationTest @Autowired constructor(
    private val rankingRepositoryPort: RankingRepositoryPort,
    private val redisTemplate: RedisTemplate<*, *>,
    private val redisCleanUp: RedisCleanUp,
) {
    @Suppress("UNCHECKED_CAST")
    private val redis = redisTemplate as RedisTemplate<String, String>

    private val date = LocalDate.of(2026, 7, 14)
    private val allBoard = RankingBoard.allOf("v1", date)
    private val snapshotBoard = RankingBoard.snapshotOf("v1", date)

    @AfterEach
    fun tearDown() {
        redisCleanUp.truncateAll()
    }

    private fun entries(score: Long, version: String = "v1") = listOf(
        BoardScore(RankingBoard.allOf(version, date), score),
        BoardScore(RankingBoard.snapshotOf(version, date), score),
    )

    @DisplayName("첫 반영이면 모든 대상 ZSET에 점수가 증가하고 true를 반환한다.")
    @Test
    fun incrementsAllBoards_whenFirstEvent() {
        val applied = rankingRepositoryPort.incrementScore("v1", entries(50L), productId = 101L, eventId = "event-1")

        assertThat(applied).isTrue()
        assertThat(redis.opsForZSet().score(allBoard.key(), "101")).isEqualTo(50.0)
        assertThat(redis.opsForZSet().score(snapshotBoard.key(), "101")).isEqualTo(50.0)
    }

    @DisplayName("같은 eventId로 재실행하면(dedup) 점수가 이중 반영되지 않고 false를 반환한다.")
    @Test
    fun skipsIncrement_whenDuplicateEventId() {
        rankingRepositoryPort.incrementScore("v1", entries(50L), productId = 101L, eventId = "event-1")

        val applied = rankingRepositoryPort.incrementScore("v1", entries(50L), productId = 101L, eventId = "event-1")

        assertThat(applied).isFalse()
        assertThat(redis.opsForZSet().score(allBoard.key(), "101")).isEqualTo(50.0)
    }

    @DisplayName("dedup 키는 버전별로 분리된다 - 같은 eventId가 v1/v2 각각 1회씩만 반영된다 (replay×collector 경합 안전).")
    @Test
    fun separatesDedupByVersion() {
        val v1First = rankingRepositoryPort.incrementScore("v1", entries(50L), productId = 101L, eventId = "event-1")
        val v2First = rankingRepositoryPort.incrementScore("v2", entries(80L, version = "v2"), productId = 101L, eventId = "event-1")
        val v2Again = rankingRepositoryPort.incrementScore("v2", entries(80L, version = "v2"), productId = 101L, eventId = "event-1")

        assertThat(v1First).isTrue()
        assertThat(v2First).isTrue()
        assertThat(v2Again).isFalse()
        assertThat(redis.opsForZSet().score(RankingBoard.allOf("v1", date).key(), "101")).isEqualTo(50.0)
        assertThat(redis.opsForZSet().score(RankingBoard.allOf("v2", date).key(), "101")).isEqualTo(80.0)
    }

    @DisplayName("다른 eventId면 같은 상품의 점수가 누적된다. 음수 delta도 그대로 반영된다.")
    @Test
    fun accumulatesScore_whenDifferentEvents() {
        rankingRepositoryPort.incrementScore("v1", entries(50L), productId = 101L, eventId = "event-1")
        rankingRepositoryPort.incrementScore("v1", entries(-50L), productId = 101L, eventId = "event-2")
        rankingRepositoryPort.incrementScore("v1", entries(10L), productId = 101L, eventId = "event-3")

        assertThat(redis.opsForZSet().score(allBoard.key(), "101")).isEqualTo(10.0)
    }

    @DisplayName("보드 1~3개 묶음이 하나의 Lua 안에서 모두 갱신된다 (이중 적재 케이스).")
    @Test
    fun incrementsUpToThreeBoards_whenDoubleWrite() {
        val tomorrow = date.plusDays(1)
        val doubleWriteEntries = listOf(
            BoardScore(allBoard, 50L),
            BoardScore(RankingBoard.allOf("v1", tomorrow), 5L),
            BoardScore(RankingBoard.snapshotOf("v1", tomorrow), 5L),
        )

        rankingRepositoryPort.incrementScore("v1", doubleWriteEntries, productId = 101L, eventId = "event-1")

        assertThat(redis.opsForZSet().score(allBoard.key(), "101")).isEqualTo(50.0)
        assertThat(redis.opsForZSet().score(RankingBoard.allOf("v1", tomorrow).key(), "101")).isEqualTo(5.0)
        assertThat(redis.opsForZSet().score(RankingBoard.snapshotOf("v1", tomorrow).key(), "101")).isEqualTo(5.0)
    }

    @DisplayName("반영 시 dedup 키와 ZSET에 TTL이 설정된다 (dedup 1일, ZSET 2일 슬라이딩).")
    @Test
    fun setsTtl_onDedupKeyAndZsets() {
        rankingRepositoryPort.incrementScore("v1", entries(50L), productId = 101L, eventId = "event-1")

        val dedupTtl = redis.getExpire("ranking:handled:v1:event-1")
        val zsetTtl = redis.getExpire(allBoard.key())
        assertThat(dedupTtl).isGreaterThan(0L).isLessThanOrEqualTo(24 * 60 * 60L)
        assertThat(zsetTtl).isGreaterThan(24 * 60 * 60L).isLessThanOrEqualTo(2 * 24 * 60 * 60L)
    }
}
