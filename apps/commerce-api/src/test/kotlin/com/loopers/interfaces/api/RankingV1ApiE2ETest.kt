package com.loopers.interfaces.api

import com.loopers.application.product.CreateProductCommand
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepositoryPort
import com.loopers.domain.product.ProductDetail
import com.loopers.domain.ranking.RankingBoard
import com.loopers.interfaces.api.product.ProductAdminApplicationServicePort
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RankingV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val brandRepositoryPort: BrandRepositoryPort,
    private val productAdminApplicationService: ProductAdminApplicationServicePort,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) masterTemplate: RedisTemplate<*, *>,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    companion object {
        private const val RANKING_ENDPOINT = "/api/v1/rankings"
        private val ZONE = ZoneId.of("Asia/Seoul")
    }

    @Suppress("UNCHECKED_CAST")
    private val redis = masterTemplate as RedisTemplate<String, String>

    private val today: LocalDate = LocalDate.now(ZONE)

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    private fun createProduct(name: String, price: Long): ProductDetail {
        val brand = brandRepositoryPort.save(Brand.create(name = "브랜드-$name", description = "d"))
        return productAdminApplicationService.createProduct(
            CreateProductCommand(name = name, price = price, description = "d", brandId = brand.id, quantity = 10),
        )
    }

    private fun seedScore(board: RankingBoard, productId: Long, score: Double) {
        redis.opsForZSet().add(board.key(), productId.toString(), score)
    }

    private fun rolloverStatusKey(date: LocalDate): String =
        "ranking:rollover:status:v1:${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"

    private fun markRolloverDone(date: LocalDate) {
        redis.opsForValue().set(rolloverStatusKey(date), "DONE")
    }

    private fun getRankings(query: String = ""): ResponseEntity<ApiResponse<Any>> =
        testRestTemplate.exchange(
            if (query.isEmpty()) RANKING_ENDPOINT else "$RANKING_ENDPOINT?$query",
            HttpMethod.GET,
            HttpEntity<Any>(HttpHeaders()),
            object : ParameterizedTypeReference<ApiResponse<Any>>() {},
        )

    private fun dateParam(date: LocalDate): String = date.format(DateTimeFormatter.BASIC_ISO_DATE)

    @DisplayName("GET /api/v1/rankings")
    @Nested
    inner class GetRankings {

        @DisplayName("당일 랭킹을 점수 내림차순으로, 상품명/가격이 병합된 상태로 반환한다.")
        @Test
        fun returnsHydratedRankingPage() {
            val first = createProduct("1등 상품", 39000L)
            val second = createProduct("2등 상품", 15000L)
            markRolloverDone(today)
            seedScore(RankingBoard.allOf("v1", today), first.id, 1280.0)
            seedScore(RankingBoard.allOf("v1", today), second.id, 500.0)

            val response = getRankings("date=${dateParam(today)}&page=1&size=20")

            val data = response.body?.data as? Map<*, *>
            val items = data?.get("items") as? List<*>
            val firstItem = items?.get(0) as? Map<*, *>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(data?.get("date")).isEqualTo(dateParam(today)) },
                { assertThat((data?.get("totalCount") as? Number)?.toLong()).isEqualTo(2L) },
                { assertThat(items).hasSize(2) },
                { assertThat(firstItem?.get("rank")).isEqualTo(1) },
                { assertThat((firstItem?.get("productId") as? Number)?.toLong()).isEqualTo(first.id) },
                { assertThat(firstItem?.get("score")).isEqualTo(1280.0) },
                { assertThat(firstItem?.get("productName")).isEqualTo("1등 상품") },
                { assertThat((firstItem?.get("price") as? Number)?.toLong()).isEqualTo(39000L) },
            )
        }

        @DisplayName("date를 생략하면 오늘 날짜로 조회한다.")
        @Test
        fun defaultsToToday_whenDateOmitted() {
            val product = createProduct("오늘 상품", 10000L)
            markRolloverDone(today)
            seedScore(RankingBoard.allOf("v1", today), product.id, 50.0)

            val response = getRankings()

            val data = response.body?.data as? Map<*, *>
            assertThat(data?.get("date")).isEqualTo(dateParam(today))
            assertThat(data?.get("items") as? List<*>).hasSize(1)
        }

        @DisplayName("과거 날짜에 랭킹 데이터가 없으면(TTL 만료 등) 빈 리스트/totalCount=0으로 응답한다.")
        @Test
        fun returnsEmpty_whenPastDateHasNoBoard() {
            val response = getRankings("date=${dateParam(today.minusDays(7))}")

            val data = response.body?.data as? Map<*, *>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat((data?.get("totalCount") as? Number)?.toLong()).isEqualTo(0L) },
                { assertThat(data?.get("items") as? List<*>).isEmpty() },
            )
        }

        @DisplayName("date 형식이 잘못되면 400을 반환한다.")
        @Test
        fun returns400_whenInvalidDateFormat() {
            val response = getRankings("date=2026-07-14")

            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }

        @DisplayName("page가 1 미만이거나 size가 범위를 벗어나면 400을 반환한다.")
        @Test
        fun returns400_whenInvalidPaging() {
            assertThat(getRankings("page=0").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(getRankings("size=0").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(getRankings("size=101").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @DisplayName("이월 실패 폴백 - ")
    @Nested
    inner class RolloverFallback {

        @DisplayName("이월 status가 없으면(배치 실패) 전날 랭킹으로 폴백 응답하고, 복구가 비동기로 이월 후 DONE을 찍는다.")
        @Test
        fun fallsBackToYesterdayAndRecovers_whenRolloverNotStarted() {
            val yesterday = today.minusDays(1)
            val product = createProduct("어제의 1등", 20000L)
            seedScore(RankingBoard.allOf("v1", yesterday), product.id, 100.0)
            seedScore(RankingBoard.snapshotOf("v1", yesterday), product.id, 100.0)

            val response = getRankings("date=${dateParam(today)}")

            // 폴백: 전날 보드 데이터로 즉시 응답
            val data = response.body?.data as? Map<*, *>
            val items = data?.get("items") as? List<*>
            val firstItem = items?.get(0) as? Map<*, *>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(items).hasSize(1) },
                { assertThat((firstItem?.get("productId") as? Number)?.toLong()).isEqualTo(product.id) },
                { assertThat(firstItem?.get("score")).isEqualTo(100.0) },
            )

            // 복구: snapshot:{어제} ×0.1(floor) → 오늘 보드 생성 + status DONE 전이
            awaitUntil { redis.opsForValue().get(rolloverStatusKey(today)) == "DONE" }
            assertThat(redis.opsForZSet().score(RankingBoard.allOf("v1", today).key(), product.id.toString())).isEqualTo(10.0)
            assertThat(redis.opsForZSet().score(RankingBoard.snapshotOf("v1", today).key(), product.id.toString())).isEqualTo(10.0)

            // 복구 완료(DONE) 후 요청부터는 오늘 보드로 정상 응답
            val afterRecovery = getRankings("date=${dateParam(today)}")
            val afterData = afterRecovery.body?.data as? Map<*, *>
            val afterFirst = (afterData?.get("items") as? List<*>)?.get(0) as? Map<*, *>
            assertThat(afterFirst?.get("score")).isEqualTo(10.0)
        }

        @DisplayName("이월이 중단된 상태(status 소멸, 커서 잔존)에서 조회하면 폴백 응답 후 복구가 커서부터 이어서 완주한다 - 처리됐던 멤버는 중복 반영되지 않는다.")
        @Test
        fun resumesRecoveryFromCursor_whenRolloverInterrupted() {
            val yesterday = today.minusDays(1)
            // ZRANGE는 점수 오름차순: carried(100) → remaining(300)
            val carried = createProduct("중단 전 처리된 상품", 10000L)
            val remaining = createProduct("중단 후 남은 상품", 20000L)
            seedScore(RankingBoard.allOf("v1", yesterday), carried.id, 100.0)
            seedScore(RankingBoard.allOf("v1", yesterday), remaining.id, 300.0)
            seedScore(RankingBoard.snapshotOf("v1", yesterday), carried.id, 100.0)
            seedScore(RankingBoard.snapshotOf("v1", yesterday), remaining.id, 300.0)
            // 이전 이월 주체가 첫 멤버까지 반영하고 죽은 상황 재현: 이월분(×0.1) 반영 + 커서 1 (status는 TTL 만료로 소멸)
            seedScore(RankingBoard.allOf("v1", today), carried.id, 10.0)
            seedScore(RankingBoard.snapshotOf("v1", today), carried.id, 10.0)
            redis.opsForValue().set("ranking:rollover:cursor:v1:${today.format(DateTimeFormatter.BASIC_ISO_DATE)}", "1")

            val response = getRankings("date=${dateParam(today)}")

            // 폴백: 전날 보드 데이터로 즉시 응답
            val items = (response.body?.data as? Map<*, *>)?.get("items") as? List<*>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(items).hasSize(2) },
            )

            // 복구: 커서(1)부터 이어서 완주 → DONE. 커서 앞 멤버는 다시 만나지 않아 정확히 ×0.1 유지
            awaitUntil { redis.opsForValue().get(rolloverStatusKey(today)) == "DONE" }
            val todayAllKey = RankingBoard.allOf("v1", today).key()
            assertAll(
                { assertThat(redis.opsForZSet().score(todayAllKey, carried.id.toString())).isEqualTo(10.0) },
                { assertThat(redis.opsForZSet().score(todayAllKey, remaining.id.toString())).isEqualTo(30.0) },
                { assertThat(redis.opsForZSet().score(RankingBoard.snapshotOf("v1", today).key(), remaining.id.toString())).isEqualTo(30.0) },
                { assertThat(redis.hasKey("ranking:rollover:cursor:v1:${today.format(DateTimeFormatter.BASIC_ISO_DATE)}")).isFalse() },
            )

            // 복구 완료(DONE) 후 요청부터는 오늘 보드로 정상 응답
            val afterRecovery = getRankings("date=${dateParam(today)}")
            val afterData = afterRecovery.body?.data as? Map<*, *>
            val afterFirst = (afterData?.get("items") as? List<*>)?.get(0) as? Map<*, *>
            assertAll(
                { assertThat((afterData?.get("totalCount") as? Number)?.toLong()).isEqualTo(2L) },
                { assertThat((afterFirst?.get("productId") as? Number)?.toLong()).isEqualTo(remaining.id) },
                { assertThat(afterFirst?.get("score")).isEqualTo(30.0) },
            )
        }

        @DisplayName("이월 status가 PROGRESS면(실행 중) 복구 트리거 없이 전날 랭킹으로 폴백만 한다.")
        @Test
        fun fallsBackWithoutRecovery_whenRolloverInProgress() {
            val yesterday = today.minusDays(1)
            val product = createProduct("어제의 1등", 20000L)
            seedScore(RankingBoard.allOf("v1", yesterday), product.id, 100.0)
            seedScore(RankingBoard.snapshotOf("v1", yesterday), product.id, 100.0)
            redis.opsForValue().set(rolloverStatusKey(today), "PROGRESS")

            val response = getRankings("date=${dateParam(today)}")

            val data = response.body?.data as? Map<*, *>
            val items = data?.get("items") as? List<*>
            val firstItem = items?.get(0) as? Map<*, *>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(items).hasSize(1) },
                { assertThat(firstItem?.get("score")).isEqualTo(100.0) },
                // 실행 주체가 따로 있으므로 복구를 트리거하지 않는다 - 오늘 보드는 생성되지 않고 status도 그대로
                { assertThat(redis.hasKey(RankingBoard.allOf("v1", today).key())).isFalse() },
                { assertThat(redis.opsForValue().get(rolloverStatusKey(today))).isEqualTo("PROGRESS") },
            )
        }

        private fun awaitUntil(timeoutMs: Long = 10_000L, intervalMs: Long = 200L, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(intervalMs)
            }
            check(condition()) { "조건이 ${timeoutMs}ms 안에 충족되지 않았다." }
        }
    }

    @DisplayName("상품 상세 랭킹 병합 - ")
    @Nested
    inner class ProductDetailRankingMerge {

        @DisplayName("오늘 랭킹에 있는 상품 상세를 조회하면 ranking { rank, score }가 병합된다.")
        @Test
        fun mergesRanking_whenProductRankedToday() {
            val ranked = createProduct("랭킹 상품", 30000L)
            val other = createProduct("다른 상품", 10000L)
            seedScore(RankingBoard.allOf("v1", today), ranked.id, 500.0)
            seedScore(RankingBoard.allOf("v1", today), other.id, 900.0)

            val response = testRestTemplate.exchange(
                "/api/v1/products/${ranked.id}",
                HttpMethod.GET,
                HttpEntity<Any>(HttpHeaders()),
                object : ParameterizedTypeReference<ApiResponse<Any>>() {},
            )

            val data = response.body?.data as? Map<*, *>
            val ranking = data?.get("ranking") as? Map<*, *>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(ranking).isNotNull },
                { assertThat(ranking?.get("rank")).isEqualTo(2) },
                { assertThat(ranking?.get("score")).isEqualTo(500.0) },
            )
        }

        @DisplayName("오늘 랭킹에 없는 상품 상세는 ranking이 null이다.")
        @Test
        fun rankingIsNull_whenProductNotRanked() {
            val product = createProduct("무명 상품", 5000L)

            val response = testRestTemplate.exchange(
                "/api/v1/products/${product.id}",
                HttpMethod.GET,
                HttpEntity<Any>(HttpHeaders()),
                object : ParameterizedTypeReference<ApiResponse<Any>>() {},
            )

            val data = response.body?.data as? Map<*, *>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(data?.get("ranking")).isNull() },
            )
        }
    }
}
