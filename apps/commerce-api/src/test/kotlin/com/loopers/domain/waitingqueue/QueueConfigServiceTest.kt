package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.QueueConfig
import com.loopers.domain.waitingqueue.model.QueueConfigPatch
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.QueueConfigPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class QueueConfigServiceTest {
    private val config = mockk<QueueConfigPort>(relaxed = true)
    private val service = QueueConfigService(config)
    private val topic = QueueTopic("order")

    @DisplayName("update 는 현재 설정에 패치를 병합해 저장한다(부분 수정).")
    @Test
    fun updateMergesAndSaves() {
        every { config.get(topic) } returns QueueConfig.default() // 3000, 100, 10, 30

        val merged = service.update(topic, QueueConfigPatch(admitCountPerPoll = 50))

        assertThat(merged.admitCountPerPoll).isEqualTo(50)
        assertThat(merged.pollingIntervalMs).isEqualTo(3_000L) // 유지
        assertThat(merged.admitWindowSec).isEqualTo(10) // 유지
        verify { config.save(topic, merged) }
    }

    @DisplayName("get 은 포트 조회 결과를 그대로 반환한다.")
    @Test
    fun getDelegates() {
        val stored = QueueConfig(pollingIntervalMs = 1_000L, admitCountPerPoll = 5, admitWindowSec = 3, accessTokenTtlSec = 15)
        every { config.get(topic) } returns stored

        assertThat(service.get(topic)).isEqualTo(stored)
    }
}
