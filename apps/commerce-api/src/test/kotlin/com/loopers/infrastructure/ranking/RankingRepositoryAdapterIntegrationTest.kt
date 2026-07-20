package com.loopers.infrastructure.ranking

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.ranking.RankingBoard
import com.loopers.domain.ranking.RankingRepositoryPort
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate
import java.time.LocalDate

@SpringBootTest
class RankingRepositoryAdapterIntegrationTest @Autowired constructor(
    private val rankingRepositoryPort: RankingRepositoryPort,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) masterTemplate: RedisTemplate<*, *>,
    private val redisCleanUp: RedisCleanUp,
) {
    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    private val date = LocalDate.of(2026, 7, 14)
    private val board = RankingBoard.allOf("v1", date)

    @AfterEach
    fun tearDown() {
        redisCleanUp.truncateAll()
    }

    private fun seed(vararg scores: Pair<Long, Double>) {
        scores.forEach { (productId, score) ->
            master.opsForZSet().add(board.key(), productId.toString(), score)
        }
    }

    @DisplayName("getPage는 점수 내림차순으로 offset/limit 페이지를 반환하고, rank는 offset+1부터 매겨진다.")
    @Test
    fun returnsPageOrderedByScoreDesc() {
        seed(101L to 1280.0, 102L to 500.0, 103L to 900.0, 104L to 50.0)

        val firstPage = rankingRepositoryPort.getPage(board, offset = 0L, limit = 2L)
        val secondPage = rankingRepositoryPort.getPage(board, offset = 2L, limit = 2L)

        assertThat(firstPage.map { it.productId }).containsExactly(101L, 103L)
        assertThat(firstPage.map { it.rank }).containsExactly(1L, 2L)
        assertThat(firstPage[0].score).isEqualTo(1280.0)
        assertThat(secondPage.map { it.productId }).containsExactly(102L, 104L)
        assertThat(secondPage.map { it.rank }).containsExactly(3L, 4L)
    }

    @DisplayName("보드 키가 없으면 getPage는 빈 리스트, getTotalCount는 0을 반환한다.")
    @Test
    fun returnsEmpty_whenBoardMissing() {
        assertThat(rankingRepositoryPort.getPage(board, 0L, 20L)).isEmpty()
        assertThat(rankingRepositoryPort.getTotalCount(board)).isZero()
    }

    @DisplayName("getTotalCount는 보드의 전체 멤버 수를 반환한다.")
    @Test
    fun returnsTotalCount() {
        seed(101L to 1280.0, 102L to 500.0, 103L to 900.0)

        assertThat(rankingRepositoryPort.getTotalCount(board)).isEqualTo(3L)
    }

    @DisplayName("getEntry는 특정 상품의 rank(1-base)/score를 반환하고, 없는 상품이면 null을 반환한다.")
    @Test
    fun returnsEntryWithRank() {
        seed(101L to 1280.0, 102L to 500.0, 103L to 900.0)

        val entry = rankingRepositoryPort.getEntry(board, 103L)

        assertThat(entry).isNotNull
        assertThat(entry?.rank).isEqualTo(2L)
        assertThat(entry?.score).isEqualTo(900.0)
        assertThat(rankingRepositoryPort.getEntry(board, 999L)).isNull()
    }
}
