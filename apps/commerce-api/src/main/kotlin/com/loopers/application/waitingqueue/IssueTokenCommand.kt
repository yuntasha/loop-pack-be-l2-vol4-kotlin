package com.loopers.application.waitingqueue

/** 입장 토큰 발급 입력. 대기열 토큰만으로 신원을 확인한다. */
data class IssueTokenCommand(
    val rawWaitToken: String,
)
