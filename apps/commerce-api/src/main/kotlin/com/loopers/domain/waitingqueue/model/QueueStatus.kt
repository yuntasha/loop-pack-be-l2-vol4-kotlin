package com.loopers.domain.waitingqueue.model

/**
 * 대기열 내 사용자 상태.
 * - WAITING: 대기 중
 * - ADMITTED: 승격됨(입장 토큰 발급 가능)
 * - EXPIRED: 대기열/토큰 만료 또는 이탈
 */
enum class QueueStatus {
    WAITING,
    ADMITTED,
    EXPIRED,
}
