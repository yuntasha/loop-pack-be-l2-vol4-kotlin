package com.loopers.domain.ranking

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 날짜 단위 랭킹 보드. 가중치 버전마다 독립 보드를 가진다 (가중치 변경 시 신 버전 보드를 병행 구축).
 * scope 확장으로 브랜드/카테고리별 보드에 대응한다 (예: brand:{brandId}).
 */
data class RankingBoard(
    val date: LocalDate,
    val scope: String,
    val version: String,
) {
    fun key(): String = "ranking:$scope:$version:${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"

    companion object {
        const val SCOPE_ALL = "all"
        const val SCOPE_SNAPSHOT = "snapshot"

        fun allOf(version: String, date: LocalDate): RankingBoard = RankingBoard(date, SCOPE_ALL, version)

        fun snapshotOf(version: String, date: LocalDate): RankingBoard = RankingBoard(date, SCOPE_SNAPSHOT, version)
    }
}

/** 특정 보드에 반영할 점수 증감. */
data class BoardScore(
    val board: RankingBoard,
    val scoreDelta: Long,
)
