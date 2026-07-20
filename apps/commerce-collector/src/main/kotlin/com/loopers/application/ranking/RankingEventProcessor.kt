package com.loopers.application.ranking

import com.loopers.domain.ranking.RankingEventType
import com.loopers.domain.ranking.RankingService
import com.loopers.interfaces.consumer.ProductMetricPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class RankingEventProcessor(
    private val rankingService: RankingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun process(payload: ProductMetricPayload) {
        val type = mapToRankingEventType(payload.type)
        if (type == null) {
            log.warn("알 수 없는 메트릭 타입이라 랭킹 반영을 건너뛴다. type={}, eventId={}", payload.type, payload.eventId)
            return
        }
        rankingService.reflect(
            occurredAt = payload.occurredAt,
            productId = payload.productId,
            type = type,
            delta = payload.delta,
            eventId = payload.eventId,
        )
    }

    /** ProductMetricType(VIEW/LIKE/SALES) → RankingEventType(VIEW/LIKE/ORDER). 도메인이 발행측 표현을 모르도록 경계에서 변환한다. */
    private fun mapToRankingEventType(type: String): RankingEventType? = when (type) {
        "VIEW" -> RankingEventType.VIEW
        "LIKE" -> RankingEventType.LIKE
        "SALES" -> RankingEventType.ORDER
        else -> null
    }
}
