package com.loopers.infrastructure.ranking

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.ranking.RankingEventType
import com.loopers.domain.ranking.RankingWeightBoardsPort
import com.loopers.domain.ranking.RankingWeights
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

/**
 * ranking:weights:boards KV(JSON) 조회 어댑터. 매 이벤트마다 Redis를 왕복하지 않도록
 * 로컬 캐시(TTL 10초)를 두고, KV 미존재/파싱 실패/Redis 장애 시 기본 v1 가중치로 폴백한다 —
 * 설정 조회 장애로 랭킹 적재가 멈추면 안 된다.
 *
 * KV 예: [{"version":"v1","weights":{"VIEW":10,"LIKE":50,"ORDER":500}}, ...]
 */
@Component
class RankingWeightBoardsAdapter(
    replicaTemplate: RedisTemplate<*, *>,
    private val objectMapper: ObjectMapper,
) : RankingWeightBoardsPort {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    private val replica = replicaTemplate as RedisTemplate<String, String>

    @Volatile
    private var cached: CachedBoards? = null

    override fun getActiveBoards(): List<RankingWeights> {
        val snapshot = cached
        if (snapshot != null && System.currentTimeMillis() - snapshot.loadedAtMillis < CACHE_TTL_MILLIS) {
            return snapshot.boards
        }

        val boards = loadBoards()
        cached = CachedBoards(boards, System.currentTimeMillis())
        return boards
    }

    private fun loadBoards(): List<RankingWeights> = runCatching {
        val json = replica.opsForValue().get(BOARDS_KEY) ?: return DEFAULT_BOARDS
        parse(json).ifEmpty { DEFAULT_BOARDS }
    }.getOrElse {
        log.warn("가중치 boards KV 조회 실패 - 기본 v1 가중치로 폴백한다.", it)
        DEFAULT_BOARDS
    }

    private fun parse(json: String): List<RankingWeights> {
        val entries = objectMapper.readValue(json, Array<BoardEntry>::class.java)
        return entries.map { entry ->
            val weights = entry.weights.mapNotNull { (typeName, weight) ->
                RankingEventType.entries.find { it.name == typeName }?.let { it to weight }
            }.toMap()
            RankingWeights(entry.version, weights)
        }
    }

    private data class BoardEntry(
        val version: String,
        val weights: Map<String, Long>,
    )

    private data class CachedBoards(
        val boards: List<RankingWeights>,
        val loadedAtMillis: Long,
    )

    companion object {
        private const val BOARDS_KEY = "ranking:weights:boards"
        private const val CACHE_TTL_MILLIS = 10_000L
        private val DEFAULT_BOARDS = listOf(RankingWeights.default())
    }
}
