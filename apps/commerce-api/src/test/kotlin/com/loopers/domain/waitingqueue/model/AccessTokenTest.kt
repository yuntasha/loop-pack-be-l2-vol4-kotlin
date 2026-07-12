package com.loopers.domain.waitingqueue.model

import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AccessTokenTest {
    private val signer = object : TokenSignerPort {
        override fun sign(payload: String): String = "sig($payload)"
        override fun verify(payload: String, signature: String): Boolean = signature == sign(payload)
    }

    @Test
    fun `발급한 입장 토큰을 파싱하면 topic·userId·만료가 보존된다`() {
        val topic = QueueTopic("order")

        val issued = AccessToken.issue(topic, userId = 7L, now = 1_000L, ttlSec = 30, signer = signer)
        val parsed = AccessToken.parse(issued.raw, signer)

        assertThat(parsed.topic).isEqualTo(topic)
        assertThat(parsed.userId).isEqualTo(7L)
        assertThat(parsed.issuedAt).isEqualTo(1_000L)
        assertThat(parsed.expiresAt).isEqualTo(1_000L + 30_000L)
    }

    @Test
    fun `isExpired 는 만료 시각 기준으로 판단한다`() {
        val token = AccessToken.issue(QueueTopic("order"), userId = 1L, now = 1_000L, ttlSec = 30, signer = signer)

        assertThat(token.isExpired(now = 1_000L + 29_000L)).isFalse()
        assertThat(token.isExpired(now = 1_000L + 30_000L)).isTrue()
    }

    @Test
    fun `서명이 위조되면 UNAUTHORIZED 예외`() {
        val issued = AccessToken.issue(QueueTopic("order"), userId = 1L, now = 1L, ttlSec = 30, signer = signer)
        val tampered = issued.raw.substringBeforeLast(".") + ".forged"

        assertThatThrownBy { AccessToken.parse(tampered, signer) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }
}
