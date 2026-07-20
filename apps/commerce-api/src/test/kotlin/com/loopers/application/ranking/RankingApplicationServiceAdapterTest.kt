package com.loopers.application.ranking

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepositoryPort
import com.loopers.domain.ranking.RankingEntry
import com.loopers.domain.ranking.RankingPage
import com.loopers.domain.ranking.RankingRolloverPort
import com.loopers.domain.ranking.RankingRolloverStatus
import com.loopers.domain.ranking.RankingService
import com.loopers.domain.ranking.RankingWeightService
import com.loopers.domain.ranking.RankingWeightViewPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class RankingApplicationServiceAdapterTest {

    private lateinit var rankingService: RankingService
    private lateinit var rankingWeightService: RankingWeightService
    private lateinit var productRepositoryPort: ProductRepositoryPort
    private lateinit var rankingRolloverPort: RankingRolloverPort
    private lateinit var rankingWeightViewPort: RankingWeightViewPort
    private lateinit var adapter: RankingApplicationServiceAdapter

    private val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    private val yesterday = today.minusDays(1)

    @BeforeEach
    fun setUp() {
        rankingService = mockk()
        rankingWeightService = mockk()
        productRepositoryPort = mockk()
        rankingRolloverPort = mockk()
        rankingWeightViewPort = mockk()
        adapter = RankingApplicationServiceAdapter(
            rankingService,
            rankingWeightService,
            productRepositoryPort,
            rankingRolloverPort,
            rankingWeightViewPort,
        )

        every { productRepositoryPort.findAllByIds(any()) } returns emptyList()
        every { rankingWeightViewPort.getActiveVersion() } returns "v1"
    }

    private fun emptyPage(date: LocalDate) = RankingPage(date, 1, 20, 0L, emptyList())

    @DisplayName("status가 DONE이면 활성 버전의 오늘 보드를 정상 조회하고, 복구는 트리거되지 않는다.")
    @Test
    fun servesToday_whenRolloverDone() {
        every { rankingRolloverPort.getStatus("v1", today) } returns RankingRolloverStatus.DONE
        every { rankingService.getPage("v1", today, 1, 20) } returns emptyPage(today)

        adapter.getRankingPage(RankingPageCommand(date = today, page = 1, size = 20))

        verify(exactly = 1) { rankingService.getPage("v1", today, 1, 20) }
        verify(exactly = 0) { rankingRolloverPort.tryStart(any(), any()) }
        verify(exactly = 0) { rankingRolloverPort.tryMarkNotified(any(), any()) }
    }

    @DisplayName("과거 날짜 조회는 status 확인 없이 그 날짜로 조회한다.")
    @Test
    fun servesPastDate_withoutStatusCheck() {
        every { rankingService.getPage("v1", yesterday, 1, 20) } returns emptyPage(yesterday)

        adapter.getRankingPage(RankingPageCommand(date = yesterday, page = 1, size = 20))

        verify(exactly = 0) { rankingRolloverPort.getStatus(any(), any()) }
        verify(exactly = 1) { rankingService.getPage("v1", yesterday, 1, 20) }
    }

    @DisplayName("status가 PROGRESS면(자정 넘겨 실행 중) 전날 보드로 폴백 조회하고, 복구 트리거 없이 WARN만 남긴다.")
    @Test
    fun fallsBackWithoutRecovery_whenRolloverInProgress() {
        every { rankingRolloverPort.getStatus("v1", today) } returns RankingRolloverStatus.IN_PROGRESS
        every { rankingRolloverPort.tryMarkNotified("v1", today) } returns true
        every { rankingService.getFallbackPage("v1", yesterday, 1, 20) } returns emptyPage(yesterday)

        val result = adapter.getRankingPage(RankingPageCommand(date = null, page = 1, size = 20))

        // 응답은 요청 날짜(오늘)로 유지하되 데이터는 전날 보드에서 온다
        assertThat(result.date).isEqualTo(today)
        verify(exactly = 1) { rankingService.getFallbackPage("v1", yesterday, 1, 20) }
        verify(exactly = 1) { rankingRolloverPort.tryMarkNotified("v1", today) }
        verify(exactly = 0) { rankingRolloverPort.tryStart(any(), any()) }
        verify(exactly = 0) { rankingRolloverPort.carryOverSnapshot(any(), any(), any(), any()) }
    }

    @DisplayName("status가 없으면(배치 실패) 전날 보드로 폴백하고, PROGRESS 선점 성공 시 발급받은 소유자 토큰으로 복구를 비동기 실행 후 DONE을 찍는다.")
    @Test
    fun fallsBackAndRecovers_whenRolloverNotStarted() {
        every { rankingRolloverPort.getStatus("v1", today) } returns RankingRolloverStatus.NOT_STARTED
        every { rankingRolloverPort.tryStart("v1", today) } returns "token-1"
        every { rankingRolloverPort.tryMarkNotified("v1", today) } returns true
        every { rankingRolloverPort.carryOverSnapshot("v1", yesterday, today, "token-1") } returns true
        every { rankingRolloverPort.complete("v1", today, "token-1") } returns true
        every { rankingService.getFallbackPage("v1", yesterday, 1, 20) } returns emptyPage(yesterday)

        val result = adapter.getRankingPage(RankingPageCommand(date = null, page = 1, size = 20))

        assertThat(result.date).isEqualTo(today)
        verify(exactly = 1) { rankingService.getFallbackPage("v1", yesterday, 1, 20) }
        verify(timeout = 3_000L) { rankingRolloverPort.carryOverSnapshot("v1", yesterday, today, "token-1") }
        verify(timeout = 3_000L) { rankingRolloverPort.complete("v1", today, "token-1") }
    }

    @DisplayName("PROGRESS 선점에 실패하면(다른 주체가 방금 선점) 복구·로깅 없이 폴백 응답만 한다.")
    @Test
    fun skipsRecovery_whenStartNotAcquired() {
        every { rankingRolloverPort.getStatus("v1", today) } returns RankingRolloverStatus.NOT_STARTED
        every { rankingRolloverPort.tryStart("v1", today) } returns null
        every { rankingService.getFallbackPage("v1", yesterday, 1, 20) } returns emptyPage(yesterday)

        adapter.getRankingPage(RankingPageCommand(date = today, page = 1, size = 20))

        verify(exactly = 1) { rankingService.getFallbackPage("v1", yesterday, 1, 20) }
        verify(exactly = 0) { rankingRolloverPort.tryMarkNotified(any(), any()) }
        verify(exactly = 0) { rankingRolloverPort.carryOverSnapshot(any(), any(), any(), any()) }
    }

    @DisplayName("복구 실행이 예외로 실패하면 DONE을 찍지 않는다 - 커서가 남아 다음 주체가 이어받는다.")
    @Test
    fun doesNotComplete_whenRecoveryFails() {
        every { rankingRolloverPort.getStatus("v1", today) } returns RankingRolloverStatus.NOT_STARTED
        every { rankingRolloverPort.tryStart("v1", today) } returns "token-1"
        every { rankingRolloverPort.tryMarkNotified("v1", today) } returns true
        every { rankingRolloverPort.carryOverSnapshot("v1", yesterday, today, "token-1") } throws IllegalStateException("redis down")
        every { rankingService.getFallbackPage("v1", yesterday, 1, 20) } returns emptyPage(yesterday)

        adapter.getRankingPage(RankingPageCommand(date = today, page = 1, size = 20))

        verify(timeout = 3_000L) { rankingRolloverPort.carryOverSnapshot("v1", yesterday, today, "token-1") }
        verify(exactly = 0) { rankingRolloverPort.complete(any(), any(), any()) }
    }

    @DisplayName("복구 도중 소유권을 상실하면(carryOverSnapshot=false, 다른 주체가 인수) DONE을 찍지 않고 물러난다.")
    @Test
    fun doesNotComplete_whenOwnershipLost() {
        every { rankingRolloverPort.getStatus("v1", today) } returns RankingRolloverStatus.NOT_STARTED
        every { rankingRolloverPort.tryStart("v1", today) } returns "token-1"
        every { rankingRolloverPort.tryMarkNotified("v1", today) } returns true
        every { rankingRolloverPort.carryOverSnapshot("v1", yesterday, today, "token-1") } returns false
        every { rankingService.getFallbackPage("v1", yesterday, 1, 20) } returns emptyPage(yesterday)

        adapter.getRankingPage(RankingPageCommand(date = today, page = 1, size = 20))

        verify(timeout = 3_000L) { rankingRolloverPort.carryOverSnapshot("v1", yesterday, today, "token-1") }
        verify(exactly = 0) { rankingRolloverPort.complete(any(), any(), any()) }
    }

    @DisplayName("활성 버전이 v2면 v2의 이월 status를 확인하고 v2 보드를 조회한다.")
    @Test
    fun usesActiveVersion_forStatusAndBoard() {
        every { rankingWeightViewPort.getActiveVersion() } returns "v2"
        every { rankingRolloverPort.getStatus("v2", today) } returns RankingRolloverStatus.DONE
        every { rankingService.getPage("v2", today, 1, 20) } returns emptyPage(today)

        adapter.getRankingPage(RankingPageCommand(date = today, page = 1, size = 20))

        verify(exactly = 1) { rankingRolloverPort.getStatus("v2", today) }
        verify(exactly = 1) { rankingService.getPage("v2", today, 1, 20) }
    }

    @DisplayName("랭킹 항목은 상품 정보(findAllByIds 배치 조회)로 hydration되고, 없는 상품은 name/price가 null이다.")
    @Test
    fun hydratesItems_withProductInfo() {
        val entries = listOf(
            RankingEntry(productId = 101L, score = 1280.0, rank = 1L),
            RankingEntry(productId = 999L, score = 500.0, rank = 2L),
        )
        val product = Product(id = 101L, name = "에어맥스", price = 39000L, description = "d", brandId = 1L)
        every { rankingRolloverPort.getStatus("v1", today) } returns RankingRolloverStatus.DONE
        every { rankingService.getPage("v1", today, 1, 20) } returns RankingPage(today, 1, 20, 2L, entries)
        every { productRepositoryPort.findAllByIds(listOf(101L, 999L)) } returns listOf(product)

        val result = adapter.getRankingPage(RankingPageCommand(date = today, page = 1, size = 20))

        assertThat(result.items).hasSize(2)
        assertThat(result.items[0].productName).isEqualTo("에어맥스")
        assertThat(result.items[0].price).isEqualTo(39000L)
        assertThat(result.items[1].productName).isNull()
        assertThat(result.items[1].price).isNull()
    }
}
