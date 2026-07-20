package com.loopers.domain.ranking

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RankingServiceTest {

    private lateinit var rankingRepositoryPort: RankingRepositoryPort
    private lateinit var rankingEventInboxRepositoryPort: RankingEventInboxRepositoryPort
    private lateinit var rankingWeightBoardsPort: RankingWeightBoardsPort

    private val zone = ZoneId.of("Asia/Seoul")
    private val date = LocalDate.of(2026, 7, 14)

    @BeforeEach
    fun setUp() {
        rankingRepositoryPort = mockk()
        rankingEventInboxRepositoryPort = mockk()
        rankingWeightBoardsPort = mockk()

        every { rankingEventInboxRepositoryPort.isHandled(any()) } returns false
        every { rankingEventInboxRepositoryPort.markHandled(any()) } returns Unit
        every { rankingWeightBoardsPort.getActiveBoards() } returns listOf(RankingWeights.default())
        every { rankingRepositoryPort.incrementScore(any(), any(), any(), any()) } returns true
    }

    /** 동결 게이트가 "처리 시각"을 보므로 고정 Clock으로 서비스를 만든다. now 기본값 = occurredAt (실시간 소비). */
    private fun serviceAt(now: ZonedDateTime): RankingService = RankingService(
        rankingRepositoryPort,
        rankingEventInboxRepositoryPort,
        rankingWeightBoardsPort,
        Clock.fixed(now.toInstant(), zone),
    )

    private fun reflect(
        occurredAt: ZonedDateTime,
        now: ZonedDateTime = occurredAt,
        type: RankingEventType = RankingEventType.LIKE,
        delta: Long = 1L,
    ) {
        serviceAt(now).reflect(occurredAt = occurredAt, productId = 101L, type = type, delta = delta, eventId = "event-1")
    }

    private fun capturedEntries(version: String = "v1"): List<BoardScore> {
        val entries = slot<List<BoardScore>>()
        verify { rankingRepositoryPort.incrementScore(version, capture(entries), 101L, "event-1") }
        return entries.captured
    }

    @DisplayName("멱등 처리 - ")
    @Nested
    inner class Idempotency {
        @DisplayName("이미 Inbox에 기록된 eventId면, Redis 반영과 Inbox 기록 없이 종료한다.")
        @Test
        fun skips_whenAlreadyHandled() {
            every { rankingEventInboxRepositoryPort.isHandled("event-1") } returns true

            reflect(ZonedDateTime.parse("2026-07-14T10:00:00+09:00[Asia/Seoul]"))

            verify(exactly = 0) { rankingRepositoryPort.incrementScore(any(), any(), any(), any()) }
            verify(exactly = 0) { rankingEventInboxRepositoryPort.markHandled(any()) }
        }

        @DisplayName("Redis dedup에 걸려 incrementScore가 false를 반환해도(재시도 정상 경로), Inbox 기록은 수행된다.")
        @Test
        fun marksHandled_whenRedisDedupSkips() {
            every { rankingRepositoryPort.incrementScore(any(), any(), any(), any()) } returns false

            reflect(ZonedDateTime.parse("2026-07-14T10:00:00+09:00[Asia/Seoul]"))

            verify(exactly = 1) { rankingEventInboxRepositoryPort.markHandled("event-1") }
        }
    }

    @DisplayName("버전별 이중 적재 - ")
    @Nested
    inner class VersionedWrite {
        private val morning = ZonedDateTime.parse("2026-07-14T10:00:00+09:00[Asia/Seoul]")

        @DisplayName("boards에 2개 버전이 있으면 각 버전의 가중치로 버전마다 1회씩 적재된다.")
        @Test
        fun writesEachVersion_withOwnWeights() {
            every { rankingWeightBoardsPort.getActiveBoards() } returns listOf(
                RankingWeights.default(),
                RankingWeights("v2", mapOf(RankingEventType.LIKE to 80L)),
            )

            reflect(morning)

            val v1Entries = capturedEntries("v1")
            val v2Entries = capturedEntries("v2")
            assertThat(v1Entries).containsExactly(
                BoardScore(RankingBoard.allOf("v1", date), 50L),
                BoardScore(RankingBoard.snapshotOf("v1", date), 50L),
            )
            assertThat(v2Entries).containsExactly(
                BoardScore(RankingBoard.allOf("v2", date), 80L),
                BoardScore(RankingBoard.snapshotOf("v2", date), 80L),
            )
        }

        @DisplayName("버전 설정에 없는 이벤트 타입은 기본 가중치로 계산된다.")
        @Test
        fun fallsBackToDefaultWeight_whenTypeMissing() {
            every { rankingWeightBoardsPort.getActiveBoards() } returns listOf(
                RankingWeights("v2", mapOf(RankingEventType.LIKE to 80L)),
            )

            reflect(morning, type = RankingEventType.ORDER)

            val entries = capturedEntries("v2")
            assertThat(entries.map { it.scoreDelta }).containsOnly(500L)
        }
    }

    @DisplayName("동결 게이트(발생일 23:50) 판단 - ")
    @Nested
    inner class FreezeGate {
        @DisplayName("동결 전(처리 시각 < 23:50) 실시간 소비는 오늘 all/snapshot 2개 보드에 w×delta로 적재된다.")
        @Test
        fun writesTodayBoards_whenBeforeFreeze() {
            reflect(ZonedDateTime.parse("2026-07-14T23:49:59+09:00[Asia/Seoul]"))

            val entries = capturedEntries()
            assertThat(entries).containsExactly(
                BoardScore(RankingBoard.allOf("v1", date), 50L),
                BoardScore(RankingBoard.snapshotOf("v1", date), 50L),
            )
        }

        @DisplayName("정확히 23:50:00 이벤트부터는 이중 적재된다 - 오늘 all + 내일 all/snapshot(0.1배).")
        @Test
        fun writesDoubleBoards_whenExactlyAtFreeze() {
            reflect(ZonedDateTime.parse("2026-07-14T23:50:00+09:00[Asia/Seoul]"))

            val entries = capturedEntries()
            assertThat(entries).containsExactly(
                BoardScore(RankingBoard.allOf("v1", date), 50L),
                BoardScore(RankingBoard.allOf("v1", date.plusDays(1)), 5L),
                BoardScore(RankingBoard.snapshotOf("v1", date.plusDays(1)), 5L),
            )
        }

        @DisplayName("23:59:59 이벤트도 이중 적재 대상이다. snapshot:{오늘}에는 들어가지 않는다(동결).")
        @Test
        fun writesDoubleBoards_whenLateNight() {
            reflect(ZonedDateTime.parse("2026-07-14T23:59:59+09:00[Asia/Seoul]"))

            val entries = capturedEntries()
            assertThat(entries).hasSize(3)
            assertThat(entries.map { it.board }).doesNotContain(RankingBoard.snapshotOf("v1", date))
            assertThat(entries.map { it.board }).contains(RankingBoard.allOf("v1", date.plusDays(1)))
        }

        @DisplayName("occurredAt은 23:50 이전이지만 컨슈머 랙으로 동결 후 처리되면, snapshot:{오늘}을 건드리지 않고 이월분을 스스로 내일 보드에 넣는다.")
        @Test
        fun writesDoubleBoards_whenConsumedAfterFreezeByLag() {
            reflect(
                occurredAt = ZonedDateTime.parse("2026-07-14T23:40:00+09:00[Asia/Seoul]"),
                now = ZonedDateTime.parse("2026-07-14T23:55:00+09:00[Asia/Seoul]"),
            )

            val entries = capturedEntries()
            assertThat(entries).containsExactly(
                BoardScore(RankingBoard.allOf("v1", date), 50L),
                BoardScore(RankingBoard.allOf("v1", date.plusDays(1)), 5L),
                BoardScore(RankingBoard.snapshotOf("v1", date.plusDays(1)), 5L),
            )
        }

        @DisplayName("자정을 넘긴 지연 소비도 날짜 귀속은 occurredAt 기준(어제 all)을 유지하면서 이월분은 오늘 보드에 넣는다.")
        @Test
        fun keepsOriginDateAttribution_whenConsumedAfterMidnight() {
            reflect(
                occurredAt = ZonedDateTime.parse("2026-07-14T23:40:00+09:00[Asia/Seoul]"),
                now = ZonedDateTime.parse("2026-07-15T00:05:00+09:00[Asia/Seoul]"),
            )

            val entries = capturedEntries()
            assertThat(entries).containsExactly(
                BoardScore(RankingBoard.allOf("v1", date), 50L),
                BoardScore(RankingBoard.allOf("v1", date.plusDays(1)), 5L),
                BoardScore(RankingBoard.snapshotOf("v1", date.plusDays(1)), 5L),
            )
        }

        @DisplayName("하루 초과 랙(처리 시각 ≥ D+1일 23:50)이면 snapshot:{D+1}마저 동결된 뒤라 이월분을 생략하고 all:{D}에만 원 점수를 반영한다.")
        @Test
        fun writesOriginAllOnly_whenLaggedOverOneDay() {
            reflect(
                occurredAt = ZonedDateTime.parse("2026-07-14T10:00:00+09:00[Asia/Seoul]"),
                now = ZonedDateTime.parse("2026-07-15T23:50:00+09:00[Asia/Seoul]"),
            )

            val entries = capturedEntries()
            assertThat(entries).containsExactly(
                BoardScore(RankingBoard.allOf("v1", date), 50L),
            )
        }

        @DisplayName("occurredAt이 다른 타임존이어도 Asia/Seoul 기준으로 날짜/동결을 판단한다.")
        @Test
        fun convertsToSeoulZone_whenDifferentZone() {
            // UTC 14:55 = Seoul 23:55 → 동결 이후, 서울 기준 7/14 귀속
            reflect(ZonedDateTime.parse("2026-07-14T14:55:00Z"))

            val entries = capturedEntries()
            assertThat(entries).hasSize(3)
            assertThat(entries[0].board).isEqualTo(RankingBoard.allOf("v1", date))
        }
    }

    @DisplayName("스코어 계산 - ")
    @Nested
    inner class Score {
        private val morning = ZonedDateTime.parse("2026-07-14T10:00:00+09:00[Asia/Seoul]")

        @DisplayName("기본 가중치로 VIEW +1은 +10, LIKE +1은 +50, ORDER +1은 +500으로 적재된다(×10 저장 스케일).")
        @Test
        fun appliesWeightByType() {
            listOf(
                RankingEventType.VIEW to 10L,
                RankingEventType.LIKE to 50L,
                RankingEventType.ORDER to 500L,
            ).forEach { (type, expectedScore) ->
                val entries = slot<List<BoardScore>>()
                every { rankingRepositoryPort.incrementScore(any(), capture(entries), any(), any()) } returns true

                serviceAt(morning).reflect(morning, 101L, type, 1L, "event-$type")

                assertThat(entries.captured[0].scoreDelta).isEqualTo(expectedScore)
            }
        }

        @DisplayName("delta가 음수(좋아요 취소)면 점수도 음수로 적재된다.")
        @Test
        fun appliesNegativeScore_whenDeltaNegative() {
            reflect(morning, type = RankingEventType.LIKE, delta = -1L)

            val entries = capturedEntries()
            assertThat(entries.map { it.scoreDelta }).containsOnly(-50L)
        }

        @DisplayName("동결 이후 이월분도 delta 부호를 유지한다 (-50 → -5).")
        @Test
        fun keepsSignOnCarryOver_whenDeltaNegative() {
            reflect(ZonedDateTime.parse("2026-07-14T23:55:00+09:00[Asia/Seoul]"), type = RankingEventType.LIKE, delta = -1L)

            val entries = capturedEntries()
            assertThat(entries.map { it.scoreDelta }).containsExactly(-50L, -5L, -5L)
        }
    }
}
