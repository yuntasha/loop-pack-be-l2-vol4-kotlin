package com.loopers.application.ranking

import java.time.LocalDate

data class RankingPageCommand(
    /** null이면 오늘(Asia/Seoul) 기준으로 조회한다. */
    val date: LocalDate?,
    val page: Int,
    val size: Int,
)
