package com.loopers.interfaces.consumer

import java.time.ZonedDateTime

data class ProductMetricPayload(
    val eventId: String,
    val productId: Long,
    val type: String,
    val delta: Long,
    val occurredAt: ZonedDateTime,
)
