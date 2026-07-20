package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.RankingBoard
import com.loopers.domain.ranking.RankingEntry
import com.loopers.domain.ranking.RankingRepositoryPort
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

/**
 * 랭킹 읽기 전용 어댑터. @Primary 기본 템플릿(레플리카 우선)을 사용한다.
 */
@Component
class RankingRepositoryAdapter(
    replicaTemplate: RedisTemplate<*, *>,
) : RankingRepositoryPort {
    @Suppress("UNCHECKED_CAST")
    private val replica = replicaTemplate as RedisTemplate<String, String>

    override fun getPage(board: RankingBoard, offset: Long, limit: Long): List<RankingEntry> {
        if (limit <= 0) return emptyList()
        val tuples = replica.opsForZSet()
            .reverseRangeWithScores(board.key(), offset, offset + limit - 1)
            ?: return emptyList()
        return tuples.mapIndexedNotNull { index, tuple ->
            val member = tuple.value ?: return@mapIndexedNotNull null
            RankingEntry(
                productId = member.toLong(),
                score = tuple.score ?: 0.0,
                rank = offset + index + 1,
            )
        }
    }

    override fun getTotalCount(board: RankingBoard): Long = replica.opsForZSet().size(board.key()) ?: 0L

    override fun getEntry(board: RankingBoard, productId: Long): RankingEntry? {
        val member = productId.toString()
        val rank = replica.opsForZSet().reverseRank(board.key(), member) ?: return null
        val score = replica.opsForZSet().score(board.key(), member) ?: return null
        return RankingEntry(productId = productId, score = score, rank = rank + 1)
    }
}
