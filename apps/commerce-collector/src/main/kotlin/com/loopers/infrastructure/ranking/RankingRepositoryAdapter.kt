package com.loopers.infrastructure.ranking

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.ranking.BoardScore
import com.loopers.domain.ranking.RankingRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * Redis ZSET 랭킹 적재 어댑터. dedup(SETNX) + 다중 ZINCRBY + EXPIRE를 단일 Lua로 원자 처리한다.
 *
 * 캐시([com.loopers.infrastructure]의 CacheStore류)와 달리 실패를 삼키지 않는다 — 예외를 그대로 던져
 * 트랜잭션을 롤백시키고 Kafka 재전송으로 재시도되도록 한다.
 */
@Component
class RankingRepositoryAdapter(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
) : RankingRepositoryPort {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    private val incrementScript = DefaultRedisScript(INCREMENT_WITH_DEDUP_LUA, Long::class.java)

    override fun incrementScore(version: String, entries: List<BoardScore>, productId: Long, eventId: String): Boolean {
        val keys = buildList {
            add(dedupKey(version, eventId))
            entries.forEach { add(it.board.key()) }
        }
        val args = buildList {
            add(productId.toString())
            add(DEDUP_TTL_SECONDS.toString())
            add(ZSET_TTL_SECONDS.toString())
            entries.forEach { add(it.scoreDelta.toString()) }
        }

        val applied = master.execute(incrementScript, keys, *args.toTypedArray()) == 1L
        if (!applied) {
            log.info("이미 반영된 이벤트라 랭킹 증가를 건너뛴다. version={}, eventId={}", version, eventId)
        }
        return applied
    }

    /** 버전별로 분리 — v2 병행 구축 시 배치 replay와 collector 이중 적재가 같은 이벤트를 만나도 버전당 한 번만 반영된다. */
    private fun dedupKey(version: String, eventId: String): String = "ranking:handled:$version:$eventId"

    companion object {
        private const val DEDUP_TTL_SECONDS = 24 * 60 * 60L
        private const val ZSET_TTL_SECONDS = 2 * 24 * 60 * 60L

        /**
         * KEYS[1]    = dedup key (ranking:handled:{eventId})
         * KEYS[2..N] = zset keys (ranking:all:{D}, ranking:all:{D+1}, ranking:snapshot:{D+1}, ...)
         * ARGV[1]    = member (productId)
         * ARGV[2]    = dedup ttl seconds
         * ARGV[3]    = zset ttl seconds
         * ARGV[4..]  = KEYS[i+2]에 대응하는 score delta
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
