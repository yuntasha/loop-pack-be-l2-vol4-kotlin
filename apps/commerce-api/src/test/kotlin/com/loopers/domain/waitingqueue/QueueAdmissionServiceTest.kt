package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.AdmissionGatePort
import com.loopers.domain.waitingqueue.port.AdmissionMarkerPort
import com.loopers.domain.waitingqueue.port.QueueConfigPort
import com.loopers.domain.waitingqueue.port.WaitingQueuePort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class QueueAdmissionServiceTest {
    private val waitingQueue = mockk<WaitingQueuePort>()
    private val marker = mockk<AdmissionMarkerPort>(relaxed = true)
    private val config = mockk<QueueConfigPort>()
    private val gate = mockk<AdmissionGatePort>(relaxed = true)
    private val service = QueueAdmissionService(waitingQueue, marker, config, gate)

    private val topic = QueueTopic("order")

    @DisplayName("폴링 주기가 경과한 토픽은 상위 N명을 승격하고 마커를 남긴다.")
    @Test
    fun admitsWhenDue() {
        every { waitingQueue.topics() } returns setOf(topic)
        every { config.get(topic) } returns QueueConfig.default()
        every { gate.lastAdmittedAt(topic) } returns null
        every { waitingQueue.popTop(topic, 100) } returns listOf(1L, 2L)

        val summary = service.admitDueTopics(now = 10_000L)

        assertThat(summary.topicsProcessed).isEqualTo(1)
        assertThat(summary.totalAdmitted).isEqualTo(2)
        verify { marker.mark(topic, 1L, 10) }
        verify { marker.mark(topic, 2L, 10) }
        verify { gate.markAdmittedAt(topic, 10_000L) }
    }

    @DisplayName("폴링 주기가 지나지 않은 토픽은 건너뛴다.")
    @Test
    fun skipsWhenNotDue() {
        every { waitingQueue.topics() } returns setOf(topic)
        every { config.get(topic) } returns QueueConfig.default() // pollingIntervalMs = 3000
        every { gate.lastAdmittedAt(topic) } returns 9_000L // now - last = 1000 < 3000

        val summary = service.admitDueTopics(now = 10_000L)

        assertThat(summary.topicsProcessed).isEqualTo(0)
        assertThat(summary.totalAdmitted).isEqualTo(0)
        verify(exactly = 0) { waitingQueue.popTop(any(), any()) }
    }
}
