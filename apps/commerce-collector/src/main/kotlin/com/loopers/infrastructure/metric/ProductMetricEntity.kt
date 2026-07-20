package com.loopers.infrastructure.metric

import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(
    name = "product_metrics",
    indexes = [Index(name = "idx_product_metrics_type_count", columnList = "type, count")],
)
class ProductMetricEntity(
    @EmbeddedId
    val id: ProductMetricId,

    @Column(name = "count", nullable = false)
    var count: Long = 0L,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: ZonedDateTime,
)
