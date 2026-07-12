package com.loopers.domain.waitingqueue.model

import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import java.util.Base64

/**
 * 대기열 토큰. 순번 조회 시 신원(topic/userId)을 무상태로 확인하기 위한 서명 토큰.
 *
 * 형식: `wq.<base64url(payload)>.<signature>` — payload = `topic:userId:issuedAt`.
 * 기밀성이 아니라 위·변조 방지가 목적이므로 암호화 없이 HMAC 서명만 사용한다.
 */
data class WaitToken(
    val topic: QueueTopic,
    val userId: Long,
    val issuedAt: Long,
    val raw: String,
) {
    companion object {
        private const val PREFIX = "wq"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun issue(topic: QueueTopic, userId: Long, now: Long, signer: TokenSignerPort): WaitToken {
            val encoded = encoder.encodeToString(payload(topic, userId, now).toByteArray())
            val signature = signer.sign(encoded)
            return WaitToken(topic, userId, now, "$PREFIX.$encoded.$signature")
        }

        fun parse(raw: String, signer: TokenSignerPort): WaitToken {
            val parts = raw.split(".")
            if (parts.size != 3 || parts[0] != PREFIX) {
                throw CoreException(ErrorType.BAD_REQUEST, "잘못된 대기열 토큰 형식입니다.")
            }
            val encoded = parts[1]
            val signature = parts[2]
            if (!signer.verify(encoded, signature)) {
                throw CoreException(ErrorType.UNAUTHORIZED, "대기열 토큰 서명이 올바르지 않습니다.")
            }

            val fields = runCatching { String(decoder.decode(encoded)).split(":") }
                .getOrElse { throw CoreException(ErrorType.BAD_REQUEST, "대기열 토큰을 해석할 수 없습니다.") }
            if (fields.size != 3) {
                throw CoreException(ErrorType.BAD_REQUEST, "대기열 토큰을 해석할 수 없습니다.")
            }
            val userId = fields[1].toLongOrNull()
                ?: throw CoreException(ErrorType.BAD_REQUEST, "대기열 토큰을 해석할 수 없습니다.")
            val issuedAt = fields[2].toLongOrNull()
                ?: throw CoreException(ErrorType.BAD_REQUEST, "대기열 토큰을 해석할 수 없습니다.")
            return WaitToken(QueueTopic(fields[0]), userId, issuedAt, raw)
        }

        private fun payload(topic: QueueTopic, userId: Long, now: Long): String = "${topic.value}:$userId:$now"
    }
}
