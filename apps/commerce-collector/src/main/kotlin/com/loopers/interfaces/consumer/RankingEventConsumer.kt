package com.loopers.interfaces.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.ranking.RankingEventProcessor
import com.loopers.config.kafka.KafkaConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * product.metric.v1을 metric 컨슈머(loopers-default-consumer)와 별도 그룹으로 재구독하는 랭킹 컨슈머.
 * 메트릭 집계(MySQL)와 랭킹(Redis)의 장애 도메인을 분리한다.
 */
@Component
class RankingEventConsumer(
    private val rankingEventProcessor: RankingEventProcessor,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${product-metric.topic}"],
        groupId = GROUP_ID,
        containerFactory = KafkaConfig.BATCH_LISTENER,
    )
    fun consume(
        messages: List<ConsumerRecord<String, ByteArray>>,
        acknowledgment: Acknowledgment,
    ) {
        messages.forEach { record ->
            runCatching {
                val payload = objectMapper.readValue(record.value(), ProductMetricPayload::class.java)
                rankingEventProcessor.process(payload)
            }.onFailure {
                log.error("Failed to process ranking event message offset={}", record.offset(), it)
            }
        }
        acknowledgment.acknowledge()
    }

    companion object {
        const val GROUP_ID = "ranking-collector"
    }
}
