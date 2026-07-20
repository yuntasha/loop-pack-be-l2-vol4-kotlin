package com.loopers.domain.ranking

import java.time.LocalDate

/**
 * 이월(carry-over) 상태 조회·복구용 아웃바운드 포트. 상태 키는 정기 이월 배치(commerce-batch)와 공유해
 * 배치 실행 중 api 측 복구가 중복 진입하지 못하게 한다 (PROGRESS SET NX = 분산 락).
 * 선점 시 소유자 토큰이 발급되며, 이후 쓰기(페이지 반영·완료)는 토큰 일치 시에만 유효하다 —
 * stall로 PROGRESS가 만료돼 다른 주체가 인수한 경우 구 주체의 쓰기가 펜싱으로 차단된다.
 * 이월은 가중치 버전마다 독립적으로 수행되므로 모든 연산이 버전을 받는다.
 */
interface RankingRolloverPort {
    /** targetDate 보드의 이월 상태. DONE이 아닌 한 오늘 보드는 서빙하지 않는다. */
    fun getStatus(version: String, targetDate: LocalDate): RankingRolloverStatus

    /** PROGRESS 선점 시도(SET NX). 성공 시 소유자 토큰 반환 = 이월 실행 권한 획득. 이미 다른 주체가 실행 중이면 null. */
    fun tryStart(version: String, targetDate: LocalDate): String?

    /**
     * snapshot:{v}:{fromDate}의 각 점수를 floor(×0.1)해 all/snapshot:{v}:{toDate}에 반영한다. 결과 0점은 제외.
     * 재개 커서가 남아 있으면 그 오프셋부터 이어서 실행하고, 페이지 반영·커서 갱신·heartbeat를 원자 처리해
     * 재실행 중복을 없앤다. 순단은 페이지 연산 단위로 리트라이하며, 소진 시 status를 정리(best-effort)하고
     * 커서는 남긴 채 예외를 던진다 — 다음 주체가 커서부터 이어받는다.
     *
     * @return true = 끝까지 완주(소유 유지). false = 소유권 상실(다른 주체가 인수) — 완료 처리 없이 즉시 물러난다.
     */
    fun carryOverSnapshot(version: String, fromDate: LocalDate, toDate: LocalDate, ownerToken: String): Boolean

    /** 이월 완료 표시 — 소유 토큰이 일치할 때만 status를 DONE으로 전이하고 커서를 삭제한다. 불일치면 false. */
    fun complete(version: String, targetDate: LocalDate, ownerToken: String): Boolean

    /** 이월 미완료 WARN 로그 중복 방지 가드(SET NX). 최초 관측이면 true — 그때만 로깅한다. */
    fun tryMarkNotified(version: String, targetDate: LocalDate): Boolean
}
