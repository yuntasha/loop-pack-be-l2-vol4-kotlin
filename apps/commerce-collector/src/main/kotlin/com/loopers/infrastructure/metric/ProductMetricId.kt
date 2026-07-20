package com.loopers.infrastructure.metric

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.io.Serializable

@Embeddable
class ProductMetricId(
    @Column(name = "product_id") val productId: Long = 0L,
    @Column(name = "type") val type: String = "",
) : Serializable {
    override fun equals(other: Any?): Boolean =
        other is ProductMetricId && productId == other.productId && type == other.type

    override fun hashCode(): Int = 31 * productId.hashCode() + type.hashCode()
}
