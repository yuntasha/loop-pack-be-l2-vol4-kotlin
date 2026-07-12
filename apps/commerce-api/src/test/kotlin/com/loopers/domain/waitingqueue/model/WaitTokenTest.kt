package com.loopers.domain.waitingqueue.model

import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WaitTokenTest {
    /** 결정적 라운드트립을 위한 페이크 서명기. */
    private val signer = object : TokenSignerPort {
        override fun sign(payload: String): String = "sig($payload)"
        override fun verify(payload: String, signature: String): Boolean = signature == sign(payload)
    }

    @Test
    fun `발급한 토큰을 파싱하면 topic·userId·issuedAt 이 보존된다`() {
        val topic = QueueTopic("order")

        val issued = WaitToken.issue(topic, userId = 42L, now = 1_700_000_000_000L, signer = signer)
        val parsed = WaitToken.parse(issued.raw, signer)

        assertThat(parsed.topic).isEqualTo(topic)
        assertThat(parsed.userId).isEqualTo(42L)
        assertThat(parsed.issuedAt).isEqualTo(1_700_000_000_000L)
        assertThat(parsed.raw).isEqualTo(issued.raw)
    }

    @Test
    fun `서명이 위조되면 UNAUTHORIZED 예외`() {
        val issued = WaitToken.issue(QueueTopic("order"), userId = 1L, now = 1L, signer = signer)
        val tampered = issued.raw.substringBeforeLast(".") + ".forged-signature"

        assertThatThrownBy { WaitToken.parse(tampered, signer) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @Test
    fun `형식이 잘못되면 BAD_REQUEST 예외`() {
        assertThatThrownBy { WaitToken.parse("not-a-token", signer) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @Test
    fun `접두사가 다르면 BAD_REQUEST 예외`() {
        val issued = WaitToken.issue(QueueTopic("order"), userId = 1L, now = 1L, signer = signer)
        val wrongPrefix = "xx" + issued.raw.substring(2)

        assertThatThrownBy { WaitToken.parse(wrongPrefix, signer) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
