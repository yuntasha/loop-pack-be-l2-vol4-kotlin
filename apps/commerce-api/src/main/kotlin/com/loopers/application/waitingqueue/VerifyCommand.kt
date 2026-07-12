package com.loopers.application.waitingqueue

/** 입장 토큰 검증 입력. 인터셉터가 보호 API 통과 여부를 판단할 때 사용한다. */
data class VerifyCommand(
    val rawAccessToken: String,
    val topic: String,
)
