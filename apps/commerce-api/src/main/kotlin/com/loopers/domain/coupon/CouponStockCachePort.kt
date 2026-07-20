package com.loopers.domain.coupon

/**
 * 선착순 쿠폰 발급 수량 관리 Outbound Port.
 * 구현체는 infrastructure의 CouponStockCache(Redis).
 */
interface CouponStockCachePort {
    /** 쿠폰 생성 시 수량을 미리 적재한다(Eager Loading, stampede 방지). */
    fun initialize(couponTemplateId: Long, totalCount: Long)

    /**
     * 수량을 1 차감하고 성공 여부를 반환한다.
     * cache miss 시 [remainingCountProvider]로 잔여 수량을 계산해 초기화한다.
     */
    fun reserve(couponTemplateId: Long, remainingCountProvider: () -> Long): Boolean

    /** 발급 실패 보상용 수량 복원. */
    fun restore(couponTemplateId: Long)
}
