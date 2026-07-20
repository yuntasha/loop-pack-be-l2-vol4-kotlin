package com.loopers.application.coupon

import com.loopers.application.outbox.OutboxFactory
import com.loopers.domain.auth.AuthService
import com.loopers.domain.common.PageRequest
import com.loopers.domain.common.PageResult
import com.loopers.domain.coupon.CouponIssueRequest
import com.loopers.domain.coupon.CouponIssueRequestRepositoryPort
import com.loopers.domain.coupon.CouponStockCachePort
import com.loopers.domain.coupon.CouponTemplateService
import com.loopers.domain.coupon.UserCouponRepositoryPort
import com.loopers.domain.coupon.UserCouponService
import com.loopers.interfaces.api.coupon.CouponAdminApplicationServicePort
import com.loopers.interfaces.api.coupon.CouponApplicationServicePort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class CouponApplicationServiceAdapter(
    private val couponTemplateService: CouponTemplateService,
    private val userCouponService: UserCouponService,
    private val authService: AuthService,
    private val couponIssueRequestRepositoryPort: CouponIssueRequestRepositoryPort,
    private val userCouponRepositoryPort: UserCouponRepositoryPort,
    private val couponStockCache: CouponStockCachePort,
    private val couponIssueWriter: CouponIssueWriter,
    private val outboxFactory: OutboxFactory,
) : CouponAdminApplicationServicePort,
    CouponApplicationServicePort {

    override fun issueCoupon(userId: Long, couponId: Long): CouponIssueRequestResult {
        val template = couponTemplateService.getById(couponId)

        if (LocalDateTime.now().isAfter(template.expiredAt)) {
            throw CoreException(ErrorType.BAD_REQUEST, "만료된 쿠폰은 발급할 수 없습니다.")
        }

        if (couponIssueRequestRepositoryPort.existsByUserIdAndCouponTemplateId(userId, couponId)) {
            throw CoreException(ErrorType.CONFLICT, "이미 발급 요청된 쿠폰입니다.")
        }

        val reserved = couponStockCache.reserve(couponId) {
            val issued = userCouponRepositoryPort.countByCouponTemplateId(couponId)
            (template.totalCount - issued).coerceAtLeast(0L)
        }
        if (!reserved) {
            throw CoreException(ErrorType.BAD_REQUEST, "선착순 쿠폰이 모두 소진되었습니다.")
        }

        val idempotencyKey = UUID.randomUUID().toString()
        val request = CouponIssueRequest.pending(userId, couponId, idempotencyKey)

        return try {
            val saved = couponIssueWriter.saveAndPublish(request, outboxFactory.createCouponIssueOutbox(request))
            CouponIssueRequestResult.from(saved)
        } catch (e: DataIntegrityViolationException) {
            couponStockCache.restore(couponId)
            throw CoreException(ErrorType.CONFLICT, "이미 발급 요청된 쿠폰입니다.")
        } catch (e: Exception) {
            couponStockCache.restore(couponId)
            throw e
        }
    }

    @Transactional(readOnly = true)
    override fun getIssueStatus(userId: Long, couponId: Long): CouponIssueRequestResult {
        val request = couponIssueRequestRepositoryPort.findByUserIdAndCouponTemplateId(userId, couponId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "발급 요청을 찾을 수 없습니다.")
        return CouponIssueRequestResult.from(request)
    }

    @Transactional(readOnly = true)
    override fun getMyCoupons(userId: Long): List<MyCouponResult> {
        val userCoupons = userCouponService.getByUserId(userId)
        if (userCoupons.isEmpty()) return emptyList()

        val now = LocalDateTime.now()
        val templates = couponTemplateService.getByIds(userCoupons.map { it.couponTemplateId }.toSet())
        return userCoupons.mapNotNull { userCoupon ->
            val template = templates[userCoupon.couponTemplateId] ?: return@mapNotNull null
            MyCouponResult.of(userCoupon, template, now)
        }
    }

    @Transactional
    override fun createCoupon(command: CreateCouponCommand): CouponResult {
        val couponTemplate = couponTemplateService.create(
            name = command.name,
            type = command.type,
            value = command.value,
            minOrderAmount = command.minOrderAmount,
            expiredAt = command.expiredAt,
            totalCount = command.totalCount,
        )
        couponStockCache.initialize(couponTemplate.id, couponTemplate.totalCount)
        return CouponResult.from(couponTemplate)
    }

    @Transactional(readOnly = true)
    override fun getCoupons(pageRequest: PageRequest): PageResult<CouponResult> {
        val page = couponTemplateService.getAll(pageRequest)
        return PageResult.of(
            items = page.items.map { CouponResult.from(it) },
            pageRequest = pageRequest,
            totalElements = page.totalElements,
        )
    }

    @Transactional(readOnly = true)
    override fun getCoupon(id: Long): CouponResult {
        val couponTemplate = couponTemplateService.getById(id)
        return CouponResult.from(couponTemplate)
    }

    @Transactional
    override fun updateCoupon(command: UpdateCouponCommand): CouponResult {
        val couponTemplate = couponTemplateService.update(
            id = command.id,
            name = command.name,
            type = command.type,
            value = command.value,
            minOrderAmount = command.minOrderAmount,
            expiredAt = command.expiredAt,
        )
        return CouponResult.from(couponTemplate)
    }

    @Transactional
    override fun deleteCoupon(id: Long) {
        couponTemplateService.delete(id)
    }

    @Transactional(readOnly = true)
    override fun getCouponIssues(couponId: Long, pageRequest: PageRequest): PageResult<CouponIssueResult> {
        couponTemplateService.getById(couponId)
        val page = userCouponService.getByCouponTemplateId(couponId, pageRequest)
        val loginIds = authService.findLoginIdsByUserIds(page.items.map { it.userId }.distinct())
        val items = page.items.map { CouponIssueResult.of(it, loginIds[it.userId].orEmpty()) }
        return PageResult.of(items = items, pageRequest = pageRequest, totalElements = page.totalElements)
    }
}
