package com.loopers.application.waitingqueue

import com.loopers.domain.waitingqueue.model.AccessToken

/** 입장 토큰 발급 결과. */
data class AccessTokenResult(
    val topic: String,
    val accessToken: String,
    val expiresInSeconds: Int,
) {
    companion object {
        fun from(token: AccessToken): AccessTokenResult = AccessTokenResult(
            topic = token.topic.value,
            accessToken = token.raw,
            expiresInSeconds = ((token.expiresAt - token.issuedAt) / 1_000L).toInt(),
        )
    }
}
