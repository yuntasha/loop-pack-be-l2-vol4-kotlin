package com.loopers.application.waitingqueue

import com.loopers.domain.waitingqueue.model.WaitToken

/** 대기열 진입 결과. 발급된 대기열 토큰을 담는다. */
data class WaitTokenResult(
    val topic: String,
    val waitToken: String,
) {
    companion object {
        fun from(token: WaitToken): WaitTokenResult = WaitTokenResult(
            topic = token.topic.value,
            waitToken = token.raw,
        )
    }
}
