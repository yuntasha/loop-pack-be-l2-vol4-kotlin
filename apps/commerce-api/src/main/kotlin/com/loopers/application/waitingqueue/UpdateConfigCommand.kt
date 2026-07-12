package com.loopers.application.waitingqueue

/** 대기열 설정 부분 수정 입력. null 인 필드는 기존 값을 유지한다. */
data class UpdateConfigCommand(
    val topic: String,
    val pollingIntervalMs: Long? = null,
    val admitCountPerPoll: Int? = null,
    val admitWindowSec: Int? = null,
    val accessTokenTtlSec: Int? = null,
)
