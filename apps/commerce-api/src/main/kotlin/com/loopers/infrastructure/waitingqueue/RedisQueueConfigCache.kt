package com.loopers.infrastructure.waitingqueue

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

/**
 * 대기열 설정 캐시(HASH). 스케줄러/조회 hot path 용. write-through 로 원본(DB)과 동기화된다.
 * 이 클래스는 [QueueConfigAdapter] 내부에서만 사용된다(인프라 투명).
 */
@Component
class RedisQueueConfigCache(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
) {
    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    fun read(topic: QueueTopic): QueueConfig? {
        val entries = master.opsForHash<String, String>().entries(key(topic))
        if (entries.isEmpty()) return null
        return QueueConfig(
            pollingIntervalMs = entries[FIELD_POLLING_INTERVAL_MS]?.toLongOrNull() ?: return null,
            admitCountPerPoll = entries[FIELD_ADMIT_COUNT_PER_POLL]?.toIntOrNull() ?: return null,
            admitWindowSec = entries[FIELD_ADMIT_WINDOW_SEC]?.toIntOrNull() ?: return null,
            accessTokenTtlSec = entries[FIELD_ACCESS_TOKEN_TTL_SEC]?.toIntOrNull() ?: return null,
        )
    }

    fun write(topic: QueueTopic, config: QueueConfig) {
        master.opsForHash<String, String>().putAll(
            key(topic),
            mapOf(
                FIELD_POLLING_INTERVAL_MS to config.pollingIntervalMs.toString(),
                FIELD_ADMIT_COUNT_PER_POLL to config.admitCountPerPoll.toString(),
                FIELD_ADMIT_WINDOW_SEC to config.admitWindowSec.toString(),
                FIELD_ACCESS_TOKEN_TTL_SEC to config.accessTokenTtlSec.toString(),
            ),
        )
    }

    companion object {
        private const val FIELD_POLLING_INTERVAL_MS = "pollingIntervalMs"
        private const val FIELD_ADMIT_COUNT_PER_POLL = "admitCountPerPoll"
        private const val FIELD_ADMIT_WINDOW_SEC = "admitWindowSec"
        private const val FIELD_ACCESS_TOKEN_TTL_SEC = "accessTokenTtlSec"

        private fun key(topic: QueueTopic) = "queue:config:${topic.value}"
    }
}
