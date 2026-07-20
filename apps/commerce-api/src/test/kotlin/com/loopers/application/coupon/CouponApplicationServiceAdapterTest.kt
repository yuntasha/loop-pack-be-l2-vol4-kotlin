package com.loopers.application.coupon

import com.loopers.application.outbox.OutboxFactory
import com.loopers.domain.auth.AuthService
import com.loopers.domain.common.PageRequest
import com.loopers.domain.common.PageResult
import com.loopers.domain.coupon.CouponIssueRequest
import com.loopers.domain.coupon.CouponIssueRequestRepositoryPort
import com.loopers.domain.coupon.CouponIssueStatus
import com.loopers.domain.coupon.CouponStatus
import com.loopers.domain.coupon.CouponStockCachePort
import com.loopers.domain.coupon.CouponTemplate
import com.loopers.domain.coupon.CouponTemplateService
import com.loopers.domain.coupon.CouponType
import com.loopers.domain.coupon.UserCoupon
import com.loopers.domain.coupon.UserCouponRepositoryPort
import com.loopers.domain.coupon.UserCouponService
import com.loopers.domain.outbox.Outbox
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.time.ZonedDateTime

class CouponApplicationServiceAdapterTest {

    private lateinit var couponTemplateService: CouponTemplateService
    private lateinit var userCouponService: UserCouponService
    private lateinit var authService: AuthService
    private lateinit var couponIssueRequestRepositoryPort: CouponIssueRequestRepositoryPort
    private lateinit var userCouponRepositoryPort: UserCouponRepositoryPort
    private lateinit var couponStockCache: CouponStockCachePort
    private lateinit var couponIssueWriter: CouponIssueWriter
    private lateinit var outboxFactory: OutboxFactory
    private lateinit var couponApplicationService: CouponApplicationServiceAdapter

    private val expiredAt = LocalDateTime.parse("2026-12-31T23:59:59")

    private fun stubOutbox() = Outbox.create(
        eventId = "evt-id",
        topic = "coupon.issue.v1",
        payload = "{}",
        occurredAt = ZonedDateTime.now(),
    )

    @BeforeEach
    fun setUp() {
        couponTemplateService = mockk()
        userCouponService = mockk()
        authService = mockk()
        couponIssueRequestRepositoryPort = mockk()
        userCouponRepositoryPort = mockk()
        couponStockCache = mockk()
        couponIssueWriter = mockk()
        outboxFactory = mockk()
        couponApplicationService = CouponApplicationServiceAdapter(
            couponTemplateService = couponTemplateService,
            userCouponService = userCouponService,
            authService = authService,
            couponIssueRequestRepositoryPort = couponIssueRequestRepositoryPort,
            userCouponRepositoryPort = userCouponRepositoryPort,
            couponStockCache = couponStockCache,
            couponIssueWriter = couponIssueWriter,
            outboxFactory = outboxFactory,
        )
    }

    @DisplayName("issueCoupon은 Redis 수량 차감 후 PENDING 발급 요청을 생성하고 CouponIssueRequestResult를 반환한다.")
    @Test
    fun issueCoupon_returnsPendingResult() {
        val template = template()
        val savedRequest = CouponIssueRequest.pending(9L, 4L, "idempotency-key").copy(id = 11L)

        every { couponTemplateService.getById(4L) } returns template
        every { couponIssueRequestRepositoryPort.existsByUserIdAndCouponTemplateId(9L, 4L) } returns false
        every { couponStockCache.reserve(4L, any()) } returns true
        every { outboxFactory.createCouponIssueOutbox(any()) } returns stubOutbox()
        every { couponIssueWriter.saveAndPublish(any(), any()) } returns savedRequest

        val result = couponApplicationService.issueCoupon(userId = 9L, couponId = 4L)

        assertThat(result.id).isEqualTo(11L)
        assertThat(result.couponTemplateId).isEqualTo(4L)
        assertThat(result.userId).isEqualTo(9L)
        assertThat(result.status).isEqualTo(CouponIssueStatus.PENDING)
    }

    @DisplayName("issueCoupon은 이미 발급 요청이 있으면 CONFLICT 예외를 발생시킨다.")
    @Test
    fun issueCoupon_throwsConflict_whenAlreadyRequested() {
        val template = template()

        every { couponTemplateService.getById(4L) } returns template
        every { couponIssueRequestRepositoryPort.existsByUserIdAndCouponTemplateId(9L, 4L) } returns true

        val ex = assertThrows<CoreException> {
            couponApplicationService.issueCoupon(userId = 9L, couponId = 4L)
        }
        assertThat(ex.errorType).isEqualTo(ErrorType.CONFLICT)
        verify(exactly = 0) { couponStockCache.reserve(any(), any()) }
    }

    @DisplayName("issueCoupon은 Redis 수량이 소진되면 BAD_REQUEST 예외를 발생시킨다.")
    @Test
    fun issueCoupon_throwsBadRequest_whenStockExhausted() {
        val template = template()

        every { couponTemplateService.getById(4L) } returns template
        every { couponIssueRequestRepositoryPort.existsByUserIdAndCouponTemplateId(9L, 4L) } returns false
        every { couponStockCache.reserve(4L, any()) } returns false

        val ex = assertThrows<CoreException> {
            couponApplicationService.issueCoupon(userId = 9L, couponId = 4L)
        }
        assertThat(ex.errorType).isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("getIssueStatus는 발급 요청 상태를 CouponIssueRequestResult로 반환한다.")
    @Test
    fun getIssueStatus_returnsResult_whenExists() {
        val pending = CouponIssueRequest.pending(9L, 4L, "idem-key").copy(id = 5L)
        every { couponIssueRequestRepositoryPort.findByUserIdAndCouponTemplateId(9L, 4L) } returns pending

        val result = couponApplicationService.getIssueStatus(userId = 9L, couponId = 4L)

        assertThat(result.status).isEqualTo(CouponIssueStatus.PENDING)
        assertThat(result.failureReason).isNull()
    }

    @DisplayName("getIssueStatus는 발급 요청이 없으면 NOT_FOUND 예외를 발생시킨다.")
    @Test
    fun getIssueStatus_throwsNotFound_whenMissing() {
        every { couponIssueRequestRepositoryPort.findByUserIdAndCouponTemplateId(9L, 4L) } returns null

        val ex = assertThrows<CoreException> {
            couponApplicationService.getIssueStatus(userId = 9L, couponId = 4L)
        }
        assertThat(ex.errorType).isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("createCoupon은 command 값으로 도메인 서비스를 호출하고, CouponResult로 매핑한다.")
    @Test
    fun createCoupon_delegatesToServiceAndMapsResult() {
        val command = CreateCouponCommand(
            name = "10% 할인",
            type = CouponType.RATE,
            value = 10L,
            minOrderAmount = 10_000L,
            expiredAt = expiredAt,
            totalCount = 100L,
        )
        val created = template(id = 7L, name = "10% 할인", type = CouponType.RATE, value = 10L, totalCount = 100L)
        every {
            couponTemplateService.create(
                name = "10% 할인",
                type = CouponType.RATE,
                value = 10L,
                minOrderAmount = 10_000L,
                expiredAt = expiredAt,
                totalCount = 100L,
            )
        } returns created
        every { couponStockCache.initialize(7L, 100L) } returns Unit

        val result = couponApplicationService.createCoupon(command)

        assertThat(result.id).isEqualTo(7L)
        assertThat(result.totalCount).isEqualTo(100L)
        verify(exactly = 1) { couponStockCache.initialize(7L, 100L) }
    }

    @DisplayName("getCoupon은 도메인 서비스의 getById를 호출하고, 결과를 CouponResult로 매핑한다.")
    @Test
    fun getCoupon_delegatesToServiceAndMapsResult() {
        val t = template(id = 3L)
        every { couponTemplateService.getById(3L) } returns t

        val result = couponApplicationService.getCoupon(3L)

        assertThat(result.id).isEqualTo(3L)
        verify(exactly = 1) { couponTemplateService.getById(3L) }
    }

    @DisplayName("deleteCoupon은 도메인 서비스의 delete를 id로 위임 호출한다.")
    @Test
    fun deleteCoupon_delegatesToService() {
        every { couponTemplateService.delete(5L) } returns Unit

        couponApplicationService.deleteCoupon(5L)

        verify(exactly = 1) { couponTemplateService.delete(5L) }
    }

    @DisplayName("getCouponIssues는 템플릿 존재 확인 후 발급 내역에 loginId를 매핑한 페이지를 반환한다.")
    @Test
    fun getCouponIssues_mapsLoginIdAndReturnsPage() {
        val t = template(id = 3L)
        val issuedAt = LocalDateTime.parse("2026-06-07T10:00:00")
        val coupon1 = UserCoupon(
            id = 21L,
            couponTemplateId = 3L,
            userId = 9L,
            status = CouponStatus.AVAILABLE,
            issuedAt = issuedAt,
            usedAt = null,
        )
        val coupon2 = UserCoupon(
            id = 20L,
            couponTemplateId = 3L,
            userId = 8L,
            status = CouponStatus.USED,
            issuedAt = issuedAt,
            usedAt = LocalDateTime.parse("2026-06-07T11:00:00"),
        )
        val pageRequest = PageRequest(page = 0, size = 20)
        every { couponTemplateService.getById(3L) } returns t
        every { userCouponService.getByCouponTemplateId(3L, pageRequest) } returns
            PageResult.of(items = listOf(coupon1, coupon2), pageRequest = pageRequest, totalElements = 2L)
        every { authService.findLoginIdsByUserIds(listOf(9L, 8L)) } returns mapOf(9L to "hong", 8L to "kim")

        val result = couponApplicationService.getCouponIssues(3L, pageRequest)

        assertThat(result.totalElements).isEqualTo(2L)
        assertThat(result.items[0].id).isEqualTo(21L)
        assertThat(result.items[0].loginId).isEqualTo("hong")
        assertThat(result.items[1].loginId).isEqualTo("kim")
    }

    @DisplayName("getCouponIssues는 템플릿이 존재하지 않으면 예외를 전파하고 발급 내역을 조회하지 않는다.")
    @Test
    fun getCouponIssues_throwsWhenTemplateNotFound() {
        val pageRequest = PageRequest(page = 0, size = 20)
        every { couponTemplateService.getById(9999L) } throws CoreException(ErrorType.NOT_FOUND, "템플릿 없음")

        assertThrows<CoreException> { couponApplicationService.getCouponIssues(9999L, pageRequest) }

        verify(exactly = 0) { userCouponService.getByCouponTemplateId(any(), any()) }
    }

    private fun template(
        id: Long = 4L,
        name: String = "1만원 할인",
        type: CouponType = CouponType.FIXED,
        value: Long = 10_000L,
        minOrderAmount: Long = 30_000L,
        totalCount: Long = 100L,
    ) = CouponTemplate(
        id = id,
        name = name,
        type = type,
        value = value,
        minOrderAmount = minOrderAmount,
        expiredAt = expiredAt,
        totalCount = totalCount,
    )
}
