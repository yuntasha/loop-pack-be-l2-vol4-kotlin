package com.loopers.domain.waitingqueue.port

import com.loopers.domain.waitingqueue.model.QueueTopic

/**
 * 토픽별 대기열(Sorted Set) 아웃바운드 포트. Redis/Lua 세부는 어댑터에 은닉한다.
 */
interface WaitingQueuePort {
    /** 대기열에 등록한다(재진입 시 score 갱신 = 맨 뒤). 최초 등록 시 토픽 목록에도 추가한다. */
    fun enqueue(topic: QueueTopic, userId: Long, score: Long)

    /** 0-based 순번. 대기열에 없으면 null. */
    fun rank(topic: QueueTopic, userId: Long): Long?

    /** 대기열에서 제거한다(재진입 전 기존 위치 삭제). */
    fun remove(topic: QueueTopic, userId: Long)

    /** 앞에서부터 count 명을 꺼낸다(ZPOPMIN). 승격 대상 userId 목록을 순서대로 반환. */
    fun popTop(topic: QueueTopic, count: Int): List<Long>

    /** 사용된 적 있는 토픽 목록(스케줄러 순회 대상). */
    fun topics(): Set<QueueTopic>
}
