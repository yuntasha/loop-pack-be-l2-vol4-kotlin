package com.loopers.interfaces.api.waitingqueue

import com.loopers.application.waitingqueue.PositionQuery
import com.loopers.interfaces.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 순번/예상시간 조회. 순수 read — 입장 토큰을 발급하지 않는다.
 * 대기열 토큰(X-Queue-Wait-Token)만으로 신원을 확인하므로 별도 인증 헤더가 필요 없다.
 */
@RestController
@RequestMapping("/api/v1/queue")
class QueuePositionController(
    private val queueApplicationService: QueueApplicationServicePort,
) {
    @GetMapping("/position")
    fun position(
        @RequestHeader(HEADER_WAIT_TOKEN) waitToken: String,
    ): ApiResponse<WaitingQueueV1Dto.PositionResponse> {
        val result = queueApplicationService.position(PositionQuery(waitToken))
        return ApiResponse.success(WaitingQueueV1Dto.PositionResponse.from(result))
    }

    companion object {
        const val HEADER_WAIT_TOKEN = "X-Queue-Wait-Token"
    }
}
