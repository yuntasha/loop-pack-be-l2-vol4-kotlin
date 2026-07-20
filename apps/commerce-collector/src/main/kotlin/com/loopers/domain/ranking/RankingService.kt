package com.loopers.domain.ranking

import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 랭킹 반영 Domain Service. Inbox 멱등 확인 → 적재 대상 버전 결정 → 동결 게이트 판단(적재 대상 보드 결정) →
 * 버전별 점수 반영 → Inbox 기록을 오케스트레이션한다. 트랜잭션 경계는 application 계층이 가진다.
 *
 * 버전 간 원자성은 필요 없다 — v1과 v2는 독립 보드이고, 한쪽만 반영된 채 실패해도
 * 재시도 시 각 버전의 dedup이 중복을 막는다.
 */
class RankingService(
    private val rankingRepositoryPort: RankingRepositoryPort,
    private val rankingEventInboxRepositoryPort: RankingEventInboxRepositoryPort,
    private val rankingWeightBoardsPort: RankingWeightBoardsPort,
    private val clock: Clock,
) {
    fun reflect(occurredAt: ZonedDateTime, productId: Long, type: RankingEventType, delta: Long, eventId: String) {
        if (rankingEventInboxRepositoryPort.isHandled(eventId)) return

        rankingWeightBoardsPort.getActiveBoards().forEach { weights ->
            val entries = resolveBoardScores(weights, occurredAt, type, delta)
            rankingRepositoryPort.incrementScore(weights.version, entries, productId, eventId)
        }
        rankingEventInboxRepositoryPort.markHandled(eventId)
    }

    /**
     * 동결 게이트 기준 적재 대상 보드 결정 — 날짜 귀속은 occurredAt, 동결 판단은 처리 시각(now).
     * 발생일 D = occurredAt의 Asia/Seoul 날짜, 동결 시각 freezeAt = D일 23:50.
     * - now < freezeAt          : all/snapshot:{D} += w×delta (실시간 소비)
     * - freezeAt ≤ now < D+1 동결 : all:{D} += w×delta, all/snapshot:{D+1} += 0.1×w×delta — snapshot:{D}에는 절대 안 쓴다.
     *   occurredAt이 23:50~24:00이면 now ≥ occurredAt ≥ freezeAt이 자명하므로 기존 실시간 컷오프를 포함하는 일반화다.
     *   컨슈머 랙으로 동결 후 늦게 소비된 이벤트도 이 경로를 타 이월 배치의 오프셋 커서 전제(순회 중 snapshot 불변)를 지키고,
     *   이월분(×0.1)을 스스로 D+1에 넣어 유실도 막는다.
     * - now ≥ D+1일 23:50 (하루 초과 랙) : snapshot:{D+1}마저 동결된 뒤라 이월분은 생략하고 all:{D}에만 원 점수 반영 (감수).
     */
    private fun resolveBoardScores(
        weights: RankingWeights,
        occurredAt: ZonedDateTime,
        type: RankingEventType,
        delta: Long,
    ): List<BoardScore> {
        val date = occurredAt.withZoneSameInstant(ZONE).toLocalDate()
        val version = weights.version
        val score = weights.weightOf(type) * delta
        val now = ZonedDateTime.now(clock)

        return when {
            now.isBefore(freezeAt(date)) -> listOf(
                BoardScore(RankingBoard.allOf(version, date), score),
                BoardScore(RankingBoard.snapshotOf(version, date), score),
            )
            now.isBefore(freezeAt(date.plusDays(1))) -> {
                // 저장 가중치가 ×10 스케일이라 0.1배도 항상 정수다
                val carryScore = score / CARRY_OVER_DIVISOR
                listOf(
                    BoardScore(RankingBoard.allOf(version, date), score),
                    BoardScore(RankingBoard.allOf(version, date.plusDays(1)), carryScore),
                    BoardScore(RankingBoard.snapshotOf(version, date.plusDays(1)), carryScore),
                )
            }
            else -> listOf(BoardScore(RankingBoard.allOf(version, date), score))
        }
    }

    private fun freezeAt(date: LocalDate): ZonedDateTime = date.atTime(CUTOFF).atZone(ZONE)

    companion object {
        private val ZONE = ZoneId.of("Asia/Seoul")
        private val CUTOFF = LocalTime.of(23, 50)
        private const val CARRY_OVER_DIVISOR = 10L
    }
}
