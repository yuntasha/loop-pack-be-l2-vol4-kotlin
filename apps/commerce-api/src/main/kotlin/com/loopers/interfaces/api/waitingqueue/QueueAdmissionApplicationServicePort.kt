package com.loopers.interfaces.api.waitingqueue

import com.loopers.application.waitingqueue.AdmitSummaryResult

/**
 * 승격 처리 인바운드 포트. 스케줄러(마스터 틱)가 의존한다.
 */
interface QueueAdmissionApplicationServicePort {
    /** 폴링 주기가 경과한 토픽들을 승격한다. */
    fun admitDueTopics(now: Long): AdmitSummaryResult
}
