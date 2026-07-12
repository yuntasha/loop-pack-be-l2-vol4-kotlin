package com.loopers.domain.waitingqueue.port

/**
 * 대기열/입장 토큰의 위·변조 방지를 위한 서명 포트.
 * 구현(HMAC 등)과 serverSecret 은 인프라 어댑터에 은닉한다.
 */
interface TokenSignerPort {
    fun sign(payload: String): String

    fun verify(payload: String, signature: String): Boolean
}
