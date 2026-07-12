package com.loopers.application.waitingqueue

/** 대기열 진입 유스케이스 입력. topic 은 `@WaitingQueue`, userId 는 인증에서 온다. */
data class EnterCommand(
    val topic: String,
    val userId: Long,
)
