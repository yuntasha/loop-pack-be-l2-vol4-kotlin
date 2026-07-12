package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.domain.waitingqueue.port.WaitingQueuePort
import io.mockk.mockk
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueueEntryServiceTest {
    private val waitingQueue = mockk<WaitingQueuePort>(relaxed = true)
    private val signer = object : TokenSignerPort {
        override fun sign(payload: String): String = "sig($payload)"
        override fun verify(payload: String, signature: String): Boolean = signature == sign(payload)
    }
    private val service = QueueEntryService(waitingQueue, signer)

    @Test
    fun `기존 위치를 제거한 뒤 현재 시각으로 다시 등록한다(맨 뒤 재진입)`() {
        val topic = QueueTopic("order")

        service.enter(topic, userId = 7L, now = 1_000L)

        verifyOrder {
            waitingQueue.remove(topic, 7L)
            waitingQueue.enqueue(topic, 7L, 1_000L)
        }
    }

    @Test
    fun `진입 시 새 대기열 토큰을 발급한다`() {
        val topic = QueueTopic("order")

        val token = service.enter(topic, userId = 7L, now = 1_000L)

        assertThat(token.topic).isEqualTo(topic)
        assertThat(token.userId).isEqualTo(7L)
        assertThat(token.issuedAt).isEqualTo(1_000L)
        assertThat(token.raw).startsWith("wq.")
    }
}
