package com.loopers.interfaces.api

import com.loopers.application.coupon.CreateCouponCommand
import com.loopers.application.user.SignupCommand
import com.loopers.domain.coupon.CouponIssueRequestRepositoryPort
import com.loopers.domain.coupon.CouponType
import com.loopers.interfaces.api.coupon.CouponAdminApplicationServicePort
import com.loopers.interfaces.api.user.UserApplicationServicePort
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponIssueConcurrencyE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val userApplicationService: UserApplicationServicePort,
    private val couponAdminApplicationService: CouponAdminApplicationServicePort,
    private val couponIssueRequestRepositoryPort: CouponIssueRequestRepositoryPort,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    // 다른 테스트 클래스가 Redis 를 안 지운 채 남긴 coupon:{id}:remaining 키가, truncate 로 리셋된
    // auto_increment 와 결합해 같은 쿠폰 id 로 재사용될 수 있다. createCoupon 의 initialize 는
    // setIfAbsent 라 잔여 키가 있으면 수량이 초기화되지 않으므로, 시작 전에 반드시 비운다.
    @BeforeEach
    fun setUp() {
        redisCleanUp.truncateAll()
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    private fun signup(loginId: String, pw: String = "password1234"): Long =
        userApplicationService.signup(
            SignupCommand(
                loginId = loginId,
                rawPassword = pw,
                name = "테스터",
                birth = LocalDate.of(2000, 1, 1),
                email = "$loginId@example.com",
            ),
        ).id

    private fun createTemplate(totalCount: Long): Long =
        couponAdminApplicationService.createCoupon(
            CreateCouponCommand(
                name = "선착순 쿠폰",
                type = CouponType.FIXED,
                value = 5_000L,
                minOrderAmount = 0L,
                expiredAt = LocalDateTime.now().plusDays(30),
                totalCount = totalCount,
            ),
        ).id

    private fun issueAndGetStatus(couponId: Long, loginId: String, pw: String = "password1234"): Int {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Loopers-LoginId", loginId)
            set("X-Loopers-LoginPw", pw)
        }
        val responseType = object : ParameterizedTypeReference<ApiResponse<Map<String, Any?>>>() {}
        val response = testRestTemplate.exchange(
            "/api/v1/coupons/$couponId/issue",
            HttpMethod.POST,
            HttpEntity<Any>(headers),
            responseType,
        )
        return response.statusCode.value()
    }

    @DisplayName("10명이 동시에 발급 요청할 때, 성공한 발급 요청은 수량(2개)을 초과하지 않는다.")
    @Test
    fun doesNotExceedTotalCount_under100ConcurrentUsers() {
        val totalCount = 2L
        val userCount = 10
        val couponId = createTemplate(totalCount)

        // 유저 선생성 (직렬로)
        val loginIds = (1..userCount).map { i ->
            val loginId = "concuruser$i"
            signup(loginId)
            loginId
        }

        val successCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(10)
        val ready = CountDownLatch(userCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(userCount)

        loginIds.forEach { loginId ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    val statusCode = issueAndGetStatus(couponId, loginId)
                    if (statusCode in 200..299) successCount.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }

        ready.await()
        start.countDown()
        done.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        // 성공한 발급 요청 수가 totalCount 이하여야 한다
        assertThat(successCount.get()).isLessThanOrEqualTo(totalCount.toInt())
        // 적어도 1명은 성공해야 한다
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1)
    }

    @DisplayName("같은 유저가 10번 동시에 발급 요청해도, 발급 요청은 정확히 1건만 성공한다.")
    @Test
    fun onlyOneRequestSucceeds_whenSameUserRequestsConcurrently() {
        val couponId = createTemplate(10L)
        val loginId = "sameuser"
        val userId = signup(loginId)

        val successCount = AtomicInteger(0)
        val conflictCount = AtomicInteger(0)
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(10)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    val statusCode = issueAndGetStatus(couponId, loginId)
                    when (statusCode) {
                        in 200..299 -> successCount.incrementAndGet()
                        409 -> conflictCount.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        ready.await()
        start.countDown()
        done.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        // 정확히 1건만 성공, 나머지는 409 CONFLICT
        assertThat(successCount.get()).isEqualTo(1)
        assertThat(conflictCount.get()).isEqualTo(threadCount - 1)
        assertThat(couponIssueRequestRepositoryPort.existsByUserIdAndCouponTemplateId(userId, couponId)).isTrue()
    }
}
