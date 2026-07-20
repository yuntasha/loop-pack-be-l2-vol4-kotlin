package com.loopers.infrastructure.ranking

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import java.time.Duration

class RedisTransientRetryTest {

    private val sleeps = mutableListOf<Duration>()
    private val retry = RedisTransientRetry(sleeper = { sleeps.add(it) })

    @DisplayName("연결 장애(RedisConnectionFailureException)는 1초 → 2초 백오프로 재시도하고, 성공하면 결과를 반환한다.")
    @Test
    fun retriesConnectionFailure_withBackoff() {
        var attempts = 0

        val result = retry.execute("op") {
            attempts++
            if (attempts < 3) throw RedisConnectionFailureException("순단")
            "ok"
        }

        assertAll(
            { assertThat(result).isEqualTo("ok") },
            { assertThat(attempts).isEqualTo(3) },
            { assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2)) },
        )
    }

    @DisplayName("타임아웃(QueryTimeoutException)도 재시도 대상이다.")
    @Test
    fun retriesQueryTimeout() {
        var attempts = 0

        val result = retry.execute("op") {
            attempts++
            if (attempts < 2) throw QueryTimeoutException("timeout")
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(2)
    }

    @DisplayName("명령/코드 오류(그 외 예외)는 재시도가 무의미하므로 즉시 던진다.")
    @Test
    fun failsFast_whenNonTransient() {
        var attempts = 0

        assertThatThrownBy {
            retry.execute<Unit>("op") {
                attempts++
                throw InvalidDataAccessApiUsageException("명령 오류")
            }
        }.isInstanceOf(InvalidDataAccessApiUsageException::class.java)

        assertThat(attempts).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }

    @DisplayName("연결 장애가 지속되면 최대 3회 시도(백오프 2회) 후 마지막 예외를 던진다(포기 경로 진입).")
    @Test
    fun givesUp_whenRetriesExhausted() {
        var attempts = 0

        assertThatThrownBy {
            retry.execute<Unit>("op") {
                attempts++
                throw RedisConnectionFailureException("계속 순단")
            }
        }.isInstanceOf(RedisConnectionFailureException::class.java)

        assertThat(attempts).isEqualTo(3)
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2))
    }

    @DisplayName("isTransient는 연결·타임아웃 계열만 true로 분류한다.")
    @Test
    fun classifiesTransientExceptions() {
        assertAll(
            { assertThat(RedisTransientRetry.isTransient(RedisConnectionFailureException("x"))).isTrue() },
            { assertThat(RedisTransientRetry.isTransient(QueryTimeoutException("x"))).isTrue() },
            { assertThat(RedisTransientRetry.isTransient(InvalidDataAccessApiUsageException("x"))).isFalse() },
            { assertThat(RedisTransientRetry.isTransient(IllegalStateException("x"))).isFalse() },
        )
    }
}
