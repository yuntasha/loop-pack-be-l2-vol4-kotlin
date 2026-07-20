package com.loopers.application.product

import com.loopers.application.outbox.OutboxFactory
import com.loopers.application.outbox.OutboxWriter
import com.loopers.domain.outbox.ProductMetricType
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.TimeUnit

@Component
class ProductViewedEventListener(
    private val outboxWriter: OutboxWriter,
    private val outboxFactory: OutboxFactory,
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handle(event: ProductViewedEvent) {
        val outbox = outboxWriter.save(
            outboxFactory.createProductMetricOutbox(event.productId, ProductMetricType.VIEW, 1L),
        )
        runCatching {
            kafkaTemplate.send(outbox.topic, outbox.payload).get(5, TimeUnit.SECONDS)
            outboxWriter.markPublished(outbox.eventId)
        }.onFailure {
            log.error("Failed to publish VIEW event productId={}", event.productId, it)
        }
    }
}
