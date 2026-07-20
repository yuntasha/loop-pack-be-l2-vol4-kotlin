package com.loopers.batch.job.ranking.step

import org.slf4j.LoggerFactory
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import java.time.Duration

/**
 * Redis 순단(마스터 페일오버, 네트워크 순간 장애, 타임아웃)을 흡수하는 리트라이 정책.
 * 연결·타임아웃 계열 예외만 재시도하고, 그 외(명령/코드 오류)는 재시도가 무의미하므로 즉시 던진다.
 * 최대 3회 시도, 1초 → 2초 백오프 — 수 초 내 완료되는 마스터 페일오버를 배치 실패로 번지지 않게 넘긴다.
 * commerce-api RedisTransientRetry와 동일 정책(상수 계약) — 공용 모듈 추출은 향후 과제.
 */
class RedisTransientRetry(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> execute(operation: String, block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (!isTransient(e) || attempt >= maxAttempts) throw e
                val backoff = BACKOFFS[minOf(attempt, BACKOFFS.size) - 1]
                log.warn("Redis 순단으로 {} 실패 - {}ms 후 재시도한다. attempt={}/{}", operation, backoff.toMillis(), attempt, maxAttempts, e)
                sleeper(backoff)
                attempt++
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private val BACKOFFS = listOf(Duration.ofSeconds(1), Duration.ofSeconds(2))

        fun isTransient(e: Throwable): Boolean = e is RedisConnectionFailureException || e is QueryTimeoutException
    }
}
