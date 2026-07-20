package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.RankingWeightConfig
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(name = "ranking_weight_config")
class RankingWeightConfigEntity(
    @Id
    @Column(name = "version", length = 10)
    val version: String,

    @Column(name = "view_weight", nullable = false)
    var viewWeight: Long,

    @Column(name = "like_weight", nullable = false)
    var likeWeight: Long,

    @Column(name = "order_weight", nullable = false)
    var orderWeight: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PersistedRankingWeightStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: ZonedDateTime,

    @Column(name = "activated_at")
    var activatedAt: ZonedDateTime? = null,
) {
    fun update(domain: RankingWeightConfig) {
        viewWeight = domain.viewWeight
        likeWeight = domain.likeWeight
        orderWeight = domain.orderWeight
        status = PersistedRankingWeightStatus.from(domain.status)
        activatedAt = domain.activatedAt
    }

    fun toDomain(): RankingWeightConfig = RankingWeightConfig(
        version = version,
        viewWeight = viewWeight,
        likeWeight = likeWeight,
        orderWeight = orderWeight,
        status = status.toDomain(),
        createdAt = createdAt,
        activatedAt = activatedAt,
    )

    companion object {
        fun from(domain: RankingWeightConfig): RankingWeightConfigEntity = RankingWeightConfigEntity(
            version = domain.version,
            viewWeight = domain.viewWeight,
            likeWeight = domain.likeWeight,
            orderWeight = domain.orderWeight,
            status = PersistedRankingWeightStatus.from(domain.status),
            createdAt = domain.createdAt,
            activatedAt = domain.activatedAt,
        )
    }
}
