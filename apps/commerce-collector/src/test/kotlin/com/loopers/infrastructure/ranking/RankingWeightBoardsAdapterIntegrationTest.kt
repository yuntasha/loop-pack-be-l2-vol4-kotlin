package com.loopers.infrastructure.ranking

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.ranking.RankingEventType
import com.loopers.domain.ranking.RankingWeights
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate

@SpringBootTest
class RankingWeightBoardsAdapterIntegrationTest @Autowired constructor(
    private val redisTemplate: RedisTemplate<*, *>,
    private val objectMapper: ObjectMapper,
    private val redisCleanUp: RedisCleanUp,
) {
    @Suppress("UNCHECKED_CAST")
    private val redis = redisTemplate as RedisTemplate<String, String>

    // 로컬 캐시(10초)가 테스트 간 공유되지 않도록 어댑터를 매 테스트 새로 만든다
    private fun newAdapter() = RankingWeightBoardsAdapter(redisTemplate, objectMapper)

    @AfterEach
    fun tearDown() {
        redisCleanUp.truncateAll()
    }

    @DisplayName("boards KV가 없으면 기본 v1 가중치(10/50/500)로 폴백한다.")
    @Test
    fun fallsBackToDefault_whenKvMissing() {
        val boards = newAdapter().getActiveBoards()

        assertThat(boards).containsExactly(RankingWeights.default())
    }

    @DisplayName("boards KV의 버전별 가중치를 파싱해 반환한다.")
    @Test
    fun parsesVersionedWeights_fromKv() {
        redis.opsForValue().set(
            "ranking:weights:boards",
            """[{"version":"v1","weights":{"VIEW":10,"LIKE":50,"ORDER":500}},{"version":"v2","weights":{"VIEW":20,"LIKE":80,"ORDER":400}}]""",
        )

        val boards = newAdapter().getActiveBoards()

        assertThat(boards).hasSize(2)
        assertThat(boards[0].version).isEqualTo("v1")
        assertThat(boards[1].version).isEqualTo("v2")
        assertThat(boards[1].weightOf(RankingEventType.VIEW)).isEqualTo(20L)
        assertThat(boards[1].weightOf(RankingEventType.LIKE)).isEqualTo(80L)
        assertThat(boards[1].weightOf(RankingEventType.ORDER)).isEqualTo(400L)
    }

    @DisplayName("KV가 깨진 JSON이면 기본 v1 가중치로 폴백한다 (적재는 멈추지 않는다).")
    @Test
    fun fallsBackToDefault_whenKvCorrupted() {
        redis.opsForValue().set("ranking:weights:boards", "not-a-json")

        val boards = newAdapter().getActiveBoards()

        assertThat(boards).containsExactly(RankingWeights.default())
    }

    @DisplayName("조회 결과는 로컬 캐시(10초)된다 - KV를 바꿔도 캐시 TTL 안에는 이전 값을 반환한다.")
    @Test
    fun cachesResult_withinTtl() {
        val adapter = newAdapter()
        val before = adapter.getActiveBoards()

        redis.opsForValue().set(
            "ranking:weights:boards",
            """[{"version":"v2","weights":{"VIEW":20,"LIKE":80,"ORDER":400}}]""",
        )
        val after = adapter.getActiveBoards()

        assertThat(before).containsExactly(RankingWeights.default())
        assertThat(after).isEqualTo(before)
    }
}
