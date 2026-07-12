package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.AccessToken
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.AccessTokenStorePort
import com.loopers.domain.waitingqueue.port.TokenSignerPort
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AccessGuardServiceTest {
    private val signer = object : TokenSignerPort {
        override fun sign(payload: String): String = "sig($payload)"
        override fun verify(payload: String, signature: String): Boolean = signature == sign(payload)
    }
    private val accessStore = mockk<AccessTokenStorePort>()
    private val service = AccessGuardService(accessStore, signer)

    private val topic = QueueTopic("order")
    private val token = AccessToken.issue(topic, userId = 1L, now = 1_000L, ttlSec = 30, signer = signer)

    @DisplayName("서명·만료·토픽·Redis 백업이 모두 일치하면 통과한다.")
    @Test
    fun passesWhenAllValid() {
        every { accessStore.get(topic, 1L) } returns token.raw

        assertThat(service.verify(token.raw, topic, now = 2_000L)).isTrue()
    }

    @DisplayName("Redis 백업이 없으면 실패한다.")
    @Test
    fun failsWhenNoStoredToken() {
        every { accessStore.get(topic, 1L) } returns null

        assertThat(service.verify(token.raw, topic, now = 2_000L)).isFalse()
    }

    @DisplayName("Redis 백업 값이 다르면(회수됨) 실패한다.")
    @Test
    fun failsWhenStoredMismatch() {
        every { accessStore.get(topic, 1L) } returns "at.other.token"

        assertThat(service.verify(token.raw, topic, now = 2_000L)).isFalse()
    }

    @DisplayName("만료된 토큰은 실패한다.")
    @Test
    fun failsWhenExpired() {
        assertThat(service.verify(token.raw, topic, now = 1_000L + 30_000L)).isFalse()
    }

    @DisplayName("토픽이 다르면 실패한다.")
    @Test
    fun failsWhenTopicMismatch() {
        assertThat(service.verify(token.raw, QueueTopic("coupon"), now = 2_000L)).isFalse()
    }

    @DisplayName("위조/해석 불가 토큰은 실패한다.")
    @Test
    fun failsWhenForged() {
        assertThat(service.verify("at.forged.signature", topic, now = 2_000L)).isFalse()
    }
}
