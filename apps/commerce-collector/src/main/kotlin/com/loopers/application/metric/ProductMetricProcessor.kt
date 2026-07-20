package com.loopers.application.metric

import com.loopers.infrastructure.metric.EventHandledEntity
import com.loopers.infrastructure.metric.EventHandledJpaRepository
import com.loopers.infrastructure.metric.ProductMetricJpaRepository
import com.loopers.interfaces.consumer.ProductMetricPayload
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ProductMetricProcessor(
    private val productMetricJpaRepository: ProductMetricJpaRepository,
    private val eventHandledJpaRepository: EventHandledJpaRepository,
) {
    @Transactional
    fun process(payload: ProductMetricPayload) {
        if (eventHandledJpaRepository.existsById(payload.eventId)) return

        productMetricJpaRepository.upsert(
            productId = payload.productId,
            type = payload.type,
            delta = payload.delta,
            occurredAt = payload.occurredAt,
        )
        eventHandledJpaRepository.save(EventHandledEntity(eventId = payload.eventId))
    }
}
