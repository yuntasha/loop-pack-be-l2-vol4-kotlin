package com.loopers.interfaces.api.waitingqueue

import com.loopers.application.waitingqueue.AccessTokenResult
import com.loopers.application.waitingqueue.QueuePositionResult
import com.loopers.application.waitingqueue.WaitTokenResult

class WaitingQueueV1Dto {
    /** 대기열 등록(429) 시 내려주는 바디. 클라이언트는 waitToken 으로 순번을 조회한다. */
    data class EnterResponse(
        val topic: String,
        val waitToken: String,
        val message: String,
    ) {
        companion object {
            fun from(result: WaitTokenResult): EnterResponse = EnterResponse(
                topic = result.topic,
                waitToken = result.waitToken,
                message = "대기열에 등록되었습니다. GET /api/v1/queue/position 으로 순번을 확인하세요.",
            )
        }
    }

    /** 입장 토큰 발급 응답. */
    data class TokenResponse(
        val topic: String,
        val accessToken: String,
        val expiresInSeconds: Int,
    ) {
        companion object {
            fun from(result: AccessTokenResult): TokenResponse = TokenResponse(
                topic = result.topic,
                accessToken = result.accessToken,
                expiresInSeconds = result.expiresInSeconds,
            )
        }
    }

    /** 순번 조회 응답. status = WAITING | ADMITTED | EXPIRED. */
    data class PositionResponse(
        val topic: String,
        val status: String,
        val rank: Long,
        val ahead: Long,
        val estimatedWaitSeconds: Int,
        val nextPollAfterSeconds: Int,
        val admitExpiresInSeconds: Int,
    ) {
        companion object {
            fun from(result: QueuePositionResult): PositionResponse = PositionResponse(
                topic = result.topic,
                status = result.status,
                rank = result.rank,
                ahead = result.ahead,
                estimatedWaitSeconds = result.estimatedWaitSeconds,
                nextPollAfterSeconds = result.nextPollAfterSeconds,
                admitExpiresInSeconds = result.admitExpiresInSeconds,
            )
        }
    }
}
