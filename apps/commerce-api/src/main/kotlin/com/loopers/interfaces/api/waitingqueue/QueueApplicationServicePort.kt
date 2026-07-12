package com.loopers.interfaces.api.waitingqueue

import com.loopers.application.waitingqueue.AccessTokenResult
import com.loopers.application.waitingqueue.EnterCommand
import com.loopers.application.waitingqueue.IssueTokenCommand
import com.loopers.application.waitingqueue.PositionQuery
import com.loopers.application.waitingqueue.QueuePositionResult
import com.loopers.application.waitingqueue.VerifyCommand
import com.loopers.application.waitingqueue.WaitTokenResult

/**
 * 대기열 대고객 인바운드 포트. 컨트롤러/인터셉터가 의존하는 진입점.
 */
interface QueueApplicationServicePort {
    /** 대기열 (재)진입. 기존 위치를 제거하고 맨 뒤로 등록한 뒤 새 대기열 토큰을 발급한다. */
    fun enter(command: EnterCommand): WaitTokenResult

    /** 순번/예상시간 조회(순수 read). 승격 마커가 있으면 ADMITTED, 대기열에 없으면 EXPIRED. */
    fun position(query: PositionQuery): QueuePositionResult

    /** 입장 토큰 발급. 승격 마커가 있어야 발급되며, 없으면 409(NOT_ADMITTED). */
    fun issueAccessToken(command: IssueTokenCommand): AccessTokenResult

    /** 입장 토큰 검증(인터셉터 통과 여부). 서명/만료/토픽/Redis 백업 일치를 모두 만족해야 true. */
    fun verifyAccess(command: VerifyCommand): Boolean
}
