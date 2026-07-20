package com.loopers.interfaces.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.metric.ProductMetricProcessor
import com.loopers.config.kafka.KafkaConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class ProductMetricConsumer(
    private val productMetricProcessor: ProductMetricProcessor,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${product-metric.topic}"],
        containerFactory = KafkaConfig.BATCH_LISTENER,
    )
    fun consume(
        messages: List<ConsumerRecord<String, ByteArray>>,
        acknowledgment: Acknowledgment,
    ) {
        messages.forEach { record ->
            runCatching {
                val payload = objectMapper.readValue(record.value(), ProductMetricPayload::class.java)
                productMetricProcessor.process(payload)
            }.onFailure {
                log.error("Failed to process product metric message offset={}", record.offset(), it)
            }
        }
        acknowledgment.acknowledge()
    }
}
