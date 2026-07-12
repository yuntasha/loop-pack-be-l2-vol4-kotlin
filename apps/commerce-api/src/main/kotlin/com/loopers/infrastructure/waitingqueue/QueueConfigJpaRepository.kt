package com.loopers.infrastructure.waitingqueue

import org.springframework.data.jpa.repository.JpaRepository

interface QueueConfigJpaRepository : JpaRepository<QueueConfigEntity, Long> {
    fun findByTopic(topic: String): QueueConfigEntity?
}
