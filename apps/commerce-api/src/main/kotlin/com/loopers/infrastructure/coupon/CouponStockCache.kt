package com.loopers.infrastructure.coupon

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.coupon.CouponStockCachePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 선착순 쿠폰 발급 수량을 Redis로 관리하는 캐시.
 *
 * - reserve: 수량 차감(DECR). cache miss 시 SETNX 락으로 1개 서버만 DB 조회 후 초기화.
 * - restore: 발급 실패 보상용 수량 복원(INCR).
 * - initialize: 쿠폰 생성 시 Redis에 미리 적재(Eager Loading, stampede 방지).
 */
@Component
class CouponStockCache(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
) : CouponStockCachePort {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    private val master = masterTemplate as RedisTemplate<String, String>

    override fun initialize(couponTemplateId: Long, totalCount: Long) {
        master.opsForValue().setIfAbsent(remainingKey(couponTemplateId), totalCount.toString())
    }

    /**
     * 수량을 1 차감하고 성공 여부를 반환한다.
     * cache miss 시 [remainingCountProvider]로 DB 잔여 수량을 계산해 Redis를 초기화한다.
     */
    override fun reserve(couponTemplateId: Long, remainingCountProvider: () -> Long): Boolean {
        tryDecrement(couponTemplateId)?.let { return it }

        initializeWithLock(couponTemplateId, remainingCountProvider)

        return tryDecrement(couponTemplateId) ?: false
    }

    override fun restore(couponTemplateId: Long) {
        try {
            master.opsForValue().increment(remainingKey(couponTemplateId))
        } catch (e: Exception) {
            log.error("쿠폰 수량 복원 실패 couponTemplateId={}", couponTemplateId, e)
        }
    }

    private fun tryDecrement(couponTemplateId: Long): Boolean? {
        val key = remainingKey(couponTemplateId)
        if (master.hasKey(key) != true) return null

        val after = master.opsForValue().decrement(key) ?: return null
        if (after < 0) {
            master.opsForValue().increment(key)
            return false
        }
        return true
    }

    private fun initializeWithLock(couponTemplateId: Long, remainingCountProvider: () -> Long) {
        val lockKey = initLockKey(couponTemplateId)
        val acquired = master.opsForValue().setIfAbsent(lockKey, "1", 5L, TimeUnit.SECONDS)

        if (acquired == true) {
            try {
                val remaining = remainingCountProvider()
                master.opsForValue().setIfAbsent(remainingKey(couponTemplateId), remaining.toString())
            } catch (e: Exception) {
                log.error("쿠폰 수량 초기화 실패 couponTemplateId={}", couponTemplateId, e)
            } finally {
                master.delete(lockKey)
            }
        } else {
            Thread.sleep(50)
        }
    }

    private fun remainingKey(couponTemplateId: Long) = "coupon:$couponTemplateId:remaining"
    private fun initLockKey(couponTemplateId: Long) = "coupon:$couponTemplateId:init_lock"
}
