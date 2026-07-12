package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.AccessToken
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.domain.waitingqueue.port.AccessTokenStorePort
import com.loopers.domain.waitingqueue.port.TokenSignerPort

/**
 * 보호 API 입장 검증(UC4). 서명 + 만료 + 토픽 스코프 + Redis access 키 일치를 모두 만족해야 통과.
 * 어느 하나라도 어긋나면 false 를 반환해 인터셉터가 대기열 재진입(429)으로 유도하게 한다.
 */
class AccessGuardService(
    private val accessStore: AccessTokenStorePort,
    private val signer: TokenSignerPort,
) {
    fun verify(rawAccessToken: String, topic: QueueTopic, now: Long): Boolean {
        val token = runCatching { AccessToken.parse(rawAccessToken, signer) }.getOrNull() ?: return false
        if (token.topic != topic) return false
        if (token.isExpired(now)) return false

        val stored = accessStore.get(token.topic, token.userId) ?: return false
        return stored == rawAccessToken
    }
}
