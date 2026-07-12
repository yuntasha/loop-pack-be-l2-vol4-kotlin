package com.loopers.interfaces.api.waitingqueue

import com.loopers.application.waitingqueue.IssueTokenCommand
import com.loopers.interfaces.api.ApiResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 입장 토큰 발급. 조회(폴링)와 분리된 쓰기 엔드포인트.
 * 승격 마커가 있어야 발급되며(없으면 409), 대기열 토큰(X-Queue-Wait-Token)으로 신원을 확인한다.
 */
@RestController
@RequestMapping("/api/v1/queue")
class QueueTokenController(
    private val queueApplicationService: QueueApplicationServicePort,
) {
    @PostMapping("/token")
    fun issue(
        @RequestHeader(QueuePositionController.HEADER_WAIT_TOKEN) waitToken: String,
    ): ApiResponse<WaitingQueueV1Dto.TokenResponse> {
        val result = queueApplicationService.issueAccessToken(IssueTokenCommand(waitToken))
        return ApiResponse.success(WaitingQueueV1Dto.TokenResponse.from(result))
    }
}
