package com.loopers.application.ranking

import com.loopers.domain.product.ProductRepositoryPort
import com.loopers.domain.ranking.RankingPage
import com.loopers.domain.ranking.RankingRolloverPort
import com.loopers.domain.ranking.RankingRolloverStatus
import com.loopers.domain.ranking.RankingService
import com.loopers.domain.ranking.RankingWeightService
import com.loopers.domain.ranking.RankingWeightViewPort
import com.loopers.interfaces.api.ranking.RankingAdminApplicationServicePort
import com.loopers.interfaces.api.ranking.RankingApplicationServicePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

@Service
class RankingApplicationServiceAdapter(
    private val rankingService: RankingService,
    private val rankingWeightService: RankingWeightService,
    private val productRepositoryPort: ProductRepositoryPort,
    private val rankingRolloverPort: RankingRolloverPort,
    private val rankingWeightViewPort: RankingWeightViewPort,
) : RankingApplicationServicePort,
    RankingAdminApplicationServicePort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val recoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ranking-rollover-recovery").apply { isDaemon = true }
    }

    override fun getRankingPage(command: RankingPageCommand): RankingPageResult {
        val today = LocalDate.now(ZONE)
        val requestedDate = command.date ?: today
        val activeVersion = rankingWeightViewPort.getActiveVersion()
        val effective = resolveEffectiveDate(activeVersion, requestedDate, today)
        val rankingPage = if (effective.fallback) {
            rankingService.getFallbackPage(activeVersion, effective.date, command.page, command.size)
        } else {
            rankingService.getPage(activeVersion, effective.date, command.page, command.size)
        }
        return hydrate(requestedDate, rankingPage)
    }

    override fun getWeights(): List<RankingWeightResult> =
        rankingWeightService.getAll().map { RankingWeightResult.from(it) }

    @Transactional
    override fun registerWeights(command: RegisterRankingWeightCommand): RankingWeightResult =
        RankingWeightResult.from(
            rankingWeightService.register(
                version = command.version,
                viewWeight = command.viewWeight,
                likeWeight = command.likeWeight,
                orderWeight = command.orderWeight,
            ),
        )

    @Transactional
    override fun activateWeights(version: String): RankingWeightResult =
        RankingWeightResult.from(rankingWeightService.activate(version))

    @Transactional
    override fun retireWeights(version: String): RankingWeightResult =
        RankingWeightResult.from(rankingWeightService.retire(version))

    @Transactional
    override fun reopenWeights(version: String): RankingWeightResult =
        RankingWeightResult.from(rankingWeightService.reopen(version))

    /**
     * 오늘 조회는 활성 버전의 이월 status로 3-way 분기한다. 정기 배치는 전날 23:50에 시작해 자정 전에 DONE을 찍는 게
     * 정상이므로, 오늘 status가 DONE이 아니라는 관측 자체가 "자정을 넘겼는데 이월 미완료"라는 뜻 — 별도 시각 비교가 필요 없다.
     * DONE이 되기 전까지는 전날 보드("멈춘" 랭킹)로 폴백해 이월이 반쯤 진행된 어중간한 오늘 보드 노출을 막는다.
     */
    private fun resolveEffectiveDate(version: String, requestedDate: LocalDate, today: LocalDate): EffectiveDate {
        if (requestedDate != today) return EffectiveDate(requestedDate, fallback = false)

        return when (rankingRolloverPort.getStatus(version, today)) {
            RankingRolloverStatus.DONE -> EffectiveDate(today, fallback = false)
            RankingRolloverStatus.IN_PROGRESS -> {
                warnRolloverIncompleteOnce(version, today, "이월이 자정을 넘겨 아직 실행 중")
                EffectiveDate(today.minusDays(1), fallback = true)
            }
            RankingRolloverStatus.NOT_STARTED -> {
                triggerRolloverRecovery(version, today)
                EffectiveDate(today.minusDays(1), fallback = true)
            }
        }
    }

    /** 폴백 조회는 전날 보드를 읽는다 — flip 당일엔 활성 버전 전날 보드가 없을 수 있어 도메인 서비스가 타 버전 보드로 대체한다. */
    private data class EffectiveDate(
        val date: LocalDate,
        val fallback: Boolean,
    )

    private fun triggerRolloverRecovery(version: String, today: LocalDate) {
        // PROGRESS SET NX가 곧 분산 락 - 실패(null)는 다른 인스턴스/요청이 방금 선점했다는 뜻이므로 무행동
        val ownerToken = rankingRolloverPort.tryStart(version, today) ?: return

        warnRolloverIncompleteOnce(version, today, "이월 시작 흔적 없음(배치 실패) - 복구를 트리거한다")
        recoveryExecutor.execute {
            runCatching {
                // false = 소유권 상실(stall 후 다른 주체가 인수) - 새 소유자가 진행 중이므로 완료 처리 없이 물러난다
                if (rankingRolloverPort.carryOverSnapshot(version, fromDate = today.minusDays(1), toDate = today, ownerToken)) {
                    rankingRolloverPort.complete(version, today, ownerToken)
                }
            }.onFailure {
                // 순단 포기 경로는 어댑터가 status를 정리하고 커서를 남긴다 - 다음 주체(조회 트리거)가 커서부터 이어받는다
                log.error("랭킹 이월 복구 실패. version={}, targetDate={}", version, today, it)
            }
        }
    }

    /** 폴백 구간엔 매 요청이 미완료를 관측하므로 notified SET NX 가드로 최초 1회만 WARN을 남긴다. */
    private fun warnRolloverIncompleteOnce(version: String, today: LocalDate, cause: String) {
        if (rankingRolloverPort.tryMarkNotified(version, today)) {
            log.warn("랭킹 이월 미완료 감지 - 전날 보드로 폴백 중. version={}, targetDate={}, cause={}", version, today, cause)
        }
    }

    private fun hydrate(responseDate: LocalDate, rankingPage: RankingPage): RankingPageResult {
        val productIds = rankingPage.entries.map { it.productId }
        val productsById = if (productIds.isEmpty()) {
            emptyMap()
        } else {
            productRepositoryPort.findAllByIds(productIds).associateBy { it.id }
        }
        val items = rankingPage.entries.map { entry ->
            val product = productsById[entry.productId]
            RankingItemResult(
                rank = entry.rank,
                productId = entry.productId,
                score = entry.score,
                productName = product?.name,
                price = product?.price,
            )
        }
        return RankingPageResult(
            date = responseDate,
            page = rankingPage.page,
            size = rankingPage.size,
            totalCount = rankingPage.totalCount,
            items = items,
        )
    }

    companion object {
        private val ZONE = ZoneId.of("Asia/Seoul")
    }
}
