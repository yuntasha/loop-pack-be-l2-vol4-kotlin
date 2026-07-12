package com.loopers.domain.waitingqueue.model

import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import java.util.Base64

/**
 * 입장 토큰. 승격된 사용자가 보호 API 를 통과하기 위한 토큰.
 *
 * 형식: `at.<base64url(payload)>.<signature>` — payload = `topic:userId:issuedAt:expiresAt`.
 * 서명 + 만료 + 토픽 스코프를 모두 담아 위조/만료/토픽 우회를 차단한다. Redis access 키로 백업된다.
 */
data class AccessToken(
    val topic: QueueTopic,
    val userId: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val raw: String,
) {
    fun isExpired(now: Long): Boolean = now >= expiresAt

    companion object {
        private const val PREFIX = "at"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun issue(topic: QueueTopic, userId: Long, now: Long, ttlSec: Int, signer: TokenSignerPort): AccessToken {
            val expiresAt = now + ttlSec * 1_000L
            val encoded = encoder.encodeToString(payload(topic, userId, now, expiresAt).toByteArray())
            val signature = signer.sign(encoded)
            return AccessToken(topic, userId, now, expiresAt, "$PREFIX.$encoded.$signature")
        }

        fun parse(raw: String, signer: TokenSignerPort): AccessToken {
            val parts = raw.split(".")
            if (parts.size != 3 || parts[0] != PREFIX) {
                throw CoreException(ErrorType.UNAUTHORIZED, "잘못된 입장 토큰 형식입니다.")
            }
            val encoded = parts[1]
            val signature = parts[2]
            if (!signer.verify(encoded, signature)) {
                throw CoreException(ErrorType.UNAUTHORIZED, "입장 토큰 서명이 올바르지 않습니다.")
            }

            val fields = runCatching { String(decoder.decode(encoded)).split(":") }
                .getOrElse { throw CoreException(ErrorType.UNAUTHORIZED, "입장 토큰을 해석할 수 없습니다.") }
            if (fields.size != 4) {
                throw CoreException(ErrorType.UNAUTHORIZED, "입장 토큰을 해석할 수 없습니다.")
            }
            val userId = fields[1].toLongOrNull()
                ?: throw CoreException(ErrorType.UNAUTHORIZED, "입장 토큰을 해석할 수 없습니다.")
            val issuedAt = fields[2].toLongOrNull()
                ?: throw CoreException(ErrorType.UNAUTHORIZED, "입장 토큰을 해석할 수 없습니다.")
            val expiresAt = fields[3].toLongOrNull()
                ?: throw CoreException(ErrorType.UNAUTHORIZED, "입장 토큰을 해석할 수 없습니다.")
            return AccessToken(QueueTopic(fields[0]), userId, issuedAt, expiresAt, raw)
        }

        private fun payload(topic: QueueTopic, userId: Long, now: Long, expiresAt: Long): String =
            "${topic.value}:$userId:$now:$expiresAt"
    }
}
