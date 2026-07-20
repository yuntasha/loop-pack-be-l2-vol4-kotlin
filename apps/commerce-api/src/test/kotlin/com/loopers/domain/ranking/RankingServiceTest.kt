package com.loopers.domain.ranking

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RankingServiceTest {

    private lateinit var rankingRepositoryPort: RankingRepositoryPort
    private lateinit var rankingWeightViewPort: RankingWeightViewPort
    private lateinit var rankingService: RankingService

    private val date = LocalDate.of(2026, 7, 14)
    private val board = RankingBoard.allOf("v1", date)

    @BeforeEach
    fun setUp() {
        rankingRepositoryPort = mockk()
        rankingWeightViewPort = mockk()
        rankingService = RankingService(rankingRepositoryPort, rankingWeightViewPort)

        every { rankingWeightViewPort.getActiveVersion() } returns "v1"
    }

    @DisplayName("페이지 조회 시 offset=(page-1)*size로 환산해 활성 버전 보드를 조회한다.")
    @Test
    fun calculatesOffset_fromPageAndSize() {
        every { rankingRepositoryPort.getPage(board, 40L, 20L) } returns emptyList()
        every { rankingRepositoryPort.getTotalCount(board) } returns 0L

        rankingService.getPage("v1", date, page = 3, size = 20)

        verify(exactly = 1) { rankingRepositoryPort.getPage(board, 40L, 20L) }
    }

    @DisplayName("조회 결과와 totalCount를 RankingPage로 묶어 반환한다.")
    @Test
    fun composesRankingPage() {
        val entries = listOf(
            RankingEntry(productId = 101L, score = 1280.0, rank = 1L),
            RankingEntry(productId = 102L, score = 500.0, rank = 2L),
        )
        every { rankingRepositoryPort.getPage(board, 0L, 20L) } returns entries
        every { rankingRepositoryPort.getTotalCount(board) } returns 134L

        val result = rankingService.getPage("v1", date, page = 1, size = 20)

        assertThat(result.date).isEqualTo(date)
        assertThat(result.totalCount).isEqualTo(134L)
        assertThat(result.entries).isEqualTo(entries)
    }

    @DisplayName("상품 랭킹 조회 시 활성 버전 보드에 없으면 null을 반환한다.")
    @Test
    fun returnsNull_whenProductNotRanked() {
        every { rankingRepositoryPort.getEntry(board, 999L) } returns null

        assertThat(rankingService.getProductRanking(date, 999L)).isNull()
    }

    @DisplayName("상품 랭킹 조회 시 보드에 있으면 rank/score를 반환한다.")
    @Test
    fun returnsEntry_whenProductRanked() {
        val entry = RankingEntry(productId = 101L, score = 1280.0, rank = 3L)
        every { rankingRepositoryPort.getEntry(board, 101L) } returns entry

        assertThat(rankingService.getProductRanking(date, 101L)).isEqualTo(entry)
    }

    @DisplayName("폴백 조회(flip 당일 등) - ")
    @Nested
    inner class FallbackPage {
        private val v2Board = RankingBoard.allOf("v2", date)

        @DisplayName("활성 버전 보드가 비어 있지 않으면 그대로 활성 버전 보드를 서빙한다.")
        @Test
        fun servesActiveVersion_whenBoardExists() {
            every { rankingWeightViewPort.getActiveVersion() } returns "v2"
            every { rankingRepositoryPort.getTotalCount(v2Board) } returns 10L
            every { rankingRepositoryPort.getPage(v2Board, 0L, 20L) } returns emptyList()

            rankingService.getFallbackPage("v2", date, page = 1, size = 20)

            verify(exactly = 1) { rankingRepositoryPort.getPage(v2Board, 0L, 20L) }
        }

        @DisplayName("활성 버전 보드가 비어 있으면(flip 당일 전날 보드 없음) boards KV의 다른 버전 보드로 대체한다.")
        @Test
        fun fallsBackToOtherVersion_whenActiveBoardEmpty() {
            every { rankingRepositoryPort.getTotalCount(v2Board) } returns 0L
            every { rankingWeightViewPort.getBoardVersions() } returns listOf("v1", "v2")
            every { rankingRepositoryPort.getTotalCount(board) } returns 42L
            every { rankingRepositoryPort.getPage(board, 0L, 20L) } returns emptyList()

            rankingService.getFallbackPage("v2", date, page = 1, size = 20)

            verify(exactly = 1) { rankingRepositoryPort.getPage(board, 0L, 20L) }
        }

        @DisplayName("다른 버전 보드도 모두 비어 있으면 활성 버전으로 조회한다 (빈 결과).")
        @Test
        fun servesActiveVersion_whenAllBoardsEmpty() {
            every { rankingRepositoryPort.getTotalCount(any()) } returns 0L
            every { rankingWeightViewPort.getBoardVersions() } returns listOf("v1", "v2")
            every { rankingRepositoryPort.getPage(v2Board, 0L, 20L) } returns emptyList()

            val result = rankingService.getFallbackPage("v2", date, page = 1, size = 20)

            assertThat(result.totalCount).isEqualTo(0L)
            verify(exactly = 1) { rankingRepositoryPort.getPage(v2Board, 0L, 20L) }
        }
    }
}
