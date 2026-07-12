package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueStatus
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.model.WaitToken
import com.loopers.domain.waitingqueue.port.AdmissionMarkerPort
import com.loopers.domain.waitingqueue.port.QueueConfigPort
import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.domain.waitingqueue.port.WaitingQueuePort
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class QueuePositionServiceTest {
    private val signer = object : TokenSignerPort {
        override fun sign(payload: String): String = "sig($payload)"
        override fun verify(payload: String, signature: String): Boolean = signature == sign(payload)
    }
    private val waitingQueue = mockk<WaitingQueuePort>()
    private val marker = mockk<AdmissionMarkerPort>()
    private val config = mockk<QueueConfigPort>()
    private val service = QueuePositionService(waitingQueue, marker, config, signer)

    private val topic = QueueTopic("order")
    private val token = WaitToken.issue(topic, userId = 1L, now = 1_000L, signer = signer).raw

    init {
        every { config.get(topic) } returns QueueConfig.default()
    }

    @DisplayName("승격 마커가 있으면 ADMITTED 를 반환한다.")
    @Test
    fun admittedWhenMarkerExists() {
        every { marker.exists(topic, 1L) } returns true

        val position = service.position(token)

        assertThat(position.status).isEqualTo(QueueStatus.ADMITTED)
    }

    @DisplayName("대기열에 없으면 EXPIRED 를 반환한다.")
    @Test
    fun expiredWhenNotInQueue() {
        every { marker.exists(topic, 1L) } returns false
        every { waitingQueue.rank(topic, 1L) } returns null

        val position = service.position(token)

        assertThat(position.status).isEqualTo(QueueStatus.EXPIRED)
    }

    @DisplayName("대기 중이면 1-based 순번과 앞 인원을 반환한다.")
    @Test
    fun waitingWithRank() {
        every { marker.exists(topic, 1L) } returns false
        every { waitingQueue.rank(topic, 1L) } returns 5L

        val position = service.position(token)

        assertThat(position.status).isEqualTo(QueueStatus.WAITING)
        assertThat(position.rank).isEqualTo(6L)
        assertThat(position.ahead).isEqualTo(5L)
    }
}
