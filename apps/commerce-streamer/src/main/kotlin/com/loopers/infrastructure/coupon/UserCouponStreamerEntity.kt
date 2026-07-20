package com.loopers.infrastructure.coupon

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

enum class PersistedUserCouponStatus {
    ACTIVE,
    USED,
    EXPIRED,
}

@Entity
@Table(
    name = "user_coupon",
    indexes = [
        Index(name = "idx_user_coupon_user_id", columnList = "user_id"),
        Index(name = "idx_user_coupon_template_id", columnList = "coupon_template_id"),
    ],
)
class UserCouponStreamerEntity(
    @Column(name = "coupon_template_id", nullable = false)
    val couponTemplateId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: PersistedUserCouponStatus = PersistedUserCouponStatus.ACTIVE,

    @Column(name = "issued_at", nullable = false)
    val issuedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "used_at")
    var usedAt: LocalDateTime? = null,

    @Column(name = "version", nullable = false)
    var version: Long = 0L,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L,
)
