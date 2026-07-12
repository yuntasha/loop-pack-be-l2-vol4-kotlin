package com.loopers.domain.waitingqueue.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class QueuePositionTest {
    private val topic = QueueTopic("order")

    private fun config(pollingIntervalMs: Long = 3_000L, admitCountPerPoll: Int = 100) =
        QueueConfig(pollingIntervalMs, admitCountPerPoll, admitWindowSec = 10, accessTokenTtlSec = 30)

    @DisplayName("ETA = ceil(ahead / admitCountPerPoll) * (pollingIntervalMs/1000).")
    @Test
    fun calcEta() {
        // ahead=151, 100명/틱, 3초 틱 → ceil(151/100)=2 배치 → 6초
        val position = QueuePosition.waiting(topic, rank = 152, ahead = 151, config = config())

        assertThat(position.estimatedWaitSeconds).isEqualTo(6)
        assertThat(position.rank).isEqualTo(152)
        assertThat(position.ahead).isEqualTo(151)
        assertThat(position.status).isEqualTo(QueueStatus.WAITING)
    }

    @DisplayName("앞에 아무도 없으면 ETA 는 0 이다.")
    @Test
    fun etaZeroWhenNoOneAhead() {
        val position = QueuePosition.waiting(topic, rank = 1, ahead = 0, config = config())

        assertThat(position.estimatedWaitSeconds).isEqualTo(0)
        assertThat(position.nextPollAfterSeconds).isEqualTo(3)
    }

    @DisplayName("nextPollAfterSeconds: ETA 60초 미만이면 3초.")
    @Test
    fun nextPollUnder60() {
        // 19명 앞, 1명/틱, 3초 → 57초
        val position = QueuePosition.waiting(topic, rank = 20, ahead = 19, config = config(admitCountPerPoll = 1))

        assertThat(position.estimatedWaitSeconds).isEqualTo(57)
        assertThat(position.nextPollAfterSeconds).isEqualTo(3)
    }

    @DisplayName("nextPollAfterSeconds: ETA 60초 이상 300초 미만이면 12초.")
    @Test
    fun nextPollBetween60And300() {
        // 20명 앞, 1명/틱, 3초 → 60초
        val position = QueuePosition.waiting(topic, rank = 21, ahead = 20, config = config(admitCountPerPoll = 1))

        assertThat(position.estimatedWaitSeconds).isEqualTo(60)
        assertThat(position.nextPollAfterSeconds).isEqualTo(12)
    }

    @DisplayName("nextPollAfterSeconds: ETA 300초 이상이면 60초.")
    @Test
    fun nextPollOver300() {
        // 100명 앞, 1명/틱, 3초 → 300초
        val position = QueuePosition.waiting(topic, rank = 101, ahead = 100, config = config(admitCountPerPoll = 1))

        assertThat(position.estimatedWaitSeconds).isEqualTo(300)
        assertThat(position.nextPollAfterSeconds).isEqualTo(60)
    }

    @DisplayName("admitted / expired 팩토리는 각 상태를 만든다.")
    @Test
    fun admittedAndExpired() {
        assertThat(QueuePosition.admitted(topic, admitExpiresInSeconds = 10).status).isEqualTo(QueueStatus.ADMITTED)
        assertThat(QueuePosition.admitted(topic, admitExpiresInSeconds = 10).admitExpiresInSeconds).isEqualTo(10)
        assertThat(QueuePosition.expired(topic).status).isEqualTo(QueueStatus.EXPIRED)
    }
}
