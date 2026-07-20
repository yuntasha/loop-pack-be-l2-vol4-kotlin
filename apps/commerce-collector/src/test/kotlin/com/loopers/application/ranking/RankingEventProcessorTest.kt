package com.loopers.application.ranking

import com.loopers.domain.ranking.RankingEventType
import com.loopers.domain.ranking.RankingService
import com.loopers.interfaces.consumer.ProductMetricPayload
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class RankingEventProcessorTest {

    private lateinit var rankingService: RankingService
    private lateinit var rankingEventProcessor: RankingEventProcessor

    private fun payload(type: String, delta: Long = 1L) = ProductMetricPayload(
        eventId = "event-1",
        productId = 101L,
        type = type,
        delta = delta,
        occurredAt = ZonedDateTime.parse("2026-07-14T10:00:00+09:00[Asia/Seoul]"),
    )

    @BeforeEach
    fun setUp() {
        rankingService = mockk()
        rankingEventProcessor = RankingEventProcessor(rankingService)
        every { rankingService.reflect(any(), any(), any(), any(), any()) } returns Unit
    }

    @DisplayName("VIEW/LIKE 타입은 동명의 RankingEventType으로 매핑되어 반영된다.")
    @Test
    fun mapsViewAndLike() {
        rankingEventProcessor.process(payload("VIEW"))
        rankingEventProcessor.process(payload("LIKE"))

        verify(exactly = 1) { rankingService.reflect(any(), 101L, RankingEventType.VIEW, 1L, "event-1") }
        verify(exactly = 1) { rankingService.reflect(any(), 101L, RankingEventType.LIKE, 1L, "event-1") }
    }

    @DisplayName("SALES 타입은 ORDER로 매핑되어 반영된다.")
    @Test
    fun mapsSalesToOrder() {
        rankingEventProcessor.process(payload("SALES"))

        verify(exactly = 1) { rankingService.reflect(any(), 101L, RankingEventType.ORDER, 1L, "event-1") }
    }

    @DisplayName("알 수 없는 타입이면 랭킹 반영 없이 종료한다.")
    @Test
    fun skips_whenUnknownType() {
        rankingEventProcessor.process(payload("UNKNOWN"))

        verify(exactly = 0) { rankingService.reflect(any(), any(), any(), any(), any()) }
    }
}
