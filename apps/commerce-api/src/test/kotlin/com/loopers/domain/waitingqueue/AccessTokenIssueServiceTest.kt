package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.model.WaitToken
import com.loopers.domain.waitingqueue.port.AccessTokenStorePort
import com.loopers.domain.waitingqueue.port.AdmissionMarkerPort
import com.loopers.domain.waitingqueue.port.QueueConfigPort
import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AccessTokenIssueServiceTest {
    private val signer = object : TokenSignerPort {
        override fun sign(payload: String): String = "sig($payload)"
        override fun verify(payload: String, signature: String): Boolean = signature == sign(payload)
    }
    private val marker = mockk<AdmissionMarkerPort>()
    private val accessStore = mockk<AccessTokenStorePort>(relaxed = true)
    private val config = mockk<QueueConfigPort>()
    private val service = AccessTokenIssueService(marker, accessStore, config, signer)

    private val topic = QueueTopic("order")
    private val waitToken = WaitToken.issue(topic, userId = 1L, now = 1_000L, signer = signer).raw

    init {
        every { config.get(topic) } returns QueueConfig.default()
    }

    @DisplayName("승격 마커가 있으면 입장 토큰을 발급하고 Redis 에 저장한다.")
    @Test
    fun issuesWhenAdmitted() {
        every { marker.exists(topic, 1L) } returns true

        val token = service.issue(waitToken, now = 2_000L)

        assertThat(token.userId).isEqualTo(1L)
        assertThat(token.topic).isEqualTo(topic)
        assertThat(token.expiresAt).isEqualTo(2_000L + 30_000L)
        verify { accessStore.store(topic, 1L, token.raw, 30) }
    }

    @DisplayName("승격 마커가 없으면 CONFLICT 예외(NOT_ADMITTED).")
    @Test
    fun conflictWhenNotAdmitted() {
        every { marker.exists(topic, 1L) } returns false

        assertThatThrownBy { service.issue(waitToken, now = 2_000L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }
}
