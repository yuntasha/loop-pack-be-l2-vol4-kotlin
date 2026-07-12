package com.loopers.infrastructure.waitingqueue

import com.loopers.domain.waitingqueue.port.TokenSignerPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 기반 토큰 서명 어댑터. serverSecret 은 설정/환경변수로만 주입한다.
 * 검증은 타이밍 공격 방지를 위해 상수 시간 비교(MessageDigest.isEqual)를 사용한다.
 */
@Component
class HmacTokenSigner(
    @Value("\${waiting-queue.token.secret}") private val secret: String,
) : TokenSignerPort {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun sign(payload: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(), ALGORITHM))
        return encoder.encodeToString(mac.doFinal(payload.toByteArray()))
    }

    override fun verify(payload: String, signature: String): Boolean =
        MessageDigest.isEqual(sign(payload).toByteArray(), signature.toByteArray())

    companion object {
        private const val ALGORITHM = "HmacSHA256"
    }
}
