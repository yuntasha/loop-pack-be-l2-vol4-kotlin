package com.loopers.infrastructure.ranking

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.ranking.RankingWeightConfig
import com.loopers.domain.ranking.RankingWeightKvPort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

/**
 * 가중치 설정 KV 전파 어댑터. boards는 collector가 적재 대상·가중치를 결정하는 신호,
 * active는 조회측이 서빙 키를 결정하는 포인터다. 둘 다 TTL 없는 영구 키.
 *
 * boards 예: [{"version":"v1","weights":{"VIEW":10,"LIKE":50,"ORDER":500}}, ...]
 */
@Component
class RankingWeightKvAdapter(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
    private val objectMapper: ObjectMapper,
) : RankingWeightKvPort {
    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    override fun syncBoards(configs: List<RankingWeightConfig>) {
        val entries = configs.map { config ->
            mapOf(
                "version" to config.version,
                "weights" to mapOf(
                    "VIEW" to config.viewWeight,
                    "LIKE" to config.likeWeight,
                    "ORDER" to config.orderWeight,
                ),
            )
        }
        master.opsForValue().set(BOARDS_KEY, objectMapper.writeValueAsString(entries))
    }

    override fun setActive(version: String) {
        master.opsForValue().set(ACTIVE_KEY, version)
    }

    companion object {
        const val BOARDS_KEY = "ranking:weights:boards"
        const val ACTIVE_KEY = "ranking:weights:active"
    }
}
