package com.loopers.infrastructure.metric

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.ZonedDateTime

interface ProductMetricJpaRepository : JpaRepository<ProductMetricEntity, ProductMetricId> {
    @Modifying
    @Query(
        value = """
            INSERT INTO product_metrics (product_id, type, count, updated_at)
            VALUES (:productId, :type, :delta, :occurredAt)
            ON DUPLICATE KEY UPDATE
                count    = IF(:occurredAt > updated_at, count + :delta, count),
                updated_at = IF(:occurredAt > updated_at, :occurredAt, updated_at)
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("productId") productId: Long,
        @Param("type") type: String,
        @Param("delta") delta: Long,
        @Param("occurredAt") occurredAt: ZonedDateTime,
    )
}
