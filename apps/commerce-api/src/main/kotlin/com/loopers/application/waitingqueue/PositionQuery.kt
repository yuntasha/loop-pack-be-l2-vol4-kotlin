package com.loopers.application.waitingqueue

/** 순번 조회 입력. 대기열 토큰만으로 신원을 확인한다(추가 인증 불필요). */
data class PositionQuery(
    val rawWaitToken: String,
)
