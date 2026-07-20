package com.loopers.infrastructure.ranking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.ranking.RankingWeightViewPort
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

/**
 * 조회 경로용 가중치 버전 읽기 어댑터. @Primary 기본 템플릿(레플리카 우선)을 사용하며,
 * KV 미존재/장애 시 v1로 폴백한다 — 랭킹 조회가 설정 장애로 실패하면 안 된다.
 * 요청당 GET 1회는 ZREVRANGE와 같은 왕복 규모라 허용한다.
 */
@Component
class RankingWeightViewAdapter(
    replicaTemplate: RedisTemplate<*, *>,
    private val objectMapper: ObjectMapper,
) : RankingWeightViewPort {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    private val replica = replicaTemplate as RedisTemplate<String, String>

    override fun getActiveVersion(): String = runCatching {
        replica.opsForValue().get(ACTIVE_KEY)
    }.onFailure {
        log.warn("활성 가중치 버전 조회 실패 - {}로 폴백한다.", DEFAULT_VERSION, it)
    }.getOrNull() ?: DEFAULT_VERSION

    override fun getBoardVersions(): List<String> = runCatching {
        val json = replica.opsForValue().get(BOARDS_KEY) ?: return emptyList()
        objectMapper.readTree(json).mapNotNull { node: JsonNode -> node.get("version")?.asText() }
    }.getOrElse {
        log.warn("가중치 boards KV 조회 실패 - 빈 목록으로 폴백한다.", it)
        emptyList()
    }

    companion object {
        private const val ACTIVE_KEY = "ranking:weights:active"
        private const val BOARDS_KEY = "ranking:weights:boards"
        private const val DEFAULT_VERSION = "v1"
    }
}
