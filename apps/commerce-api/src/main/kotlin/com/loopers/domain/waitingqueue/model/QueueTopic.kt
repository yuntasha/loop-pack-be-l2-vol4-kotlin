package com.loopers.domain.waitingqueue.model

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 대기열 토픽. 토픽 단위로 대기열/설정/토큰이 완전히 분리된다.
 * 영문/숫자/`_`/`-` 만 허용해 Redis 키·토큰 payload 구분자와 충돌하지 않게 한다.
 */
data class QueueTopic(val value: String) {
    init {
        if (value.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "대기열 토픽은 비어 있을 수 없습니다.")
        }
        if (!PATTERN.matches(value)) {
            throw CoreException(ErrorType.BAD_REQUEST, "대기열 토픽 형식이 올바르지 않습니다: $value")
        }
    }

    companion object {
        private val PATTERN = Regex("^[A-Za-z0-9_-]+$")
    }
}
