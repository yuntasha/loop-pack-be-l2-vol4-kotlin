package com.loopers.domain.waitingqueue.port

import com.loopers.domain.waitingqueue.model.QueueTopic

/**
 * 승격 마커 아웃바운드 포트. 마커 존재 = "내 차례 됨(ADMITTED)".
 */
interface AdmissionMarkerPort {
    fun exists(topic: QueueTopic, userId: Long): Boolean

    /** 승격 마커를 TTL(admitWindowSec)과 함께 남긴다. */
    fun mark(topic: QueueTopic, userId: Long, ttlSec: Int)
}
