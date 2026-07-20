package com.loopers.interfaces.api

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.ranking.RankingBoard
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
class RankingAdminV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) masterTemplate: RedisTemplate<*, *>,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    companion object {
        private const val WEIGHTS_ENDPOINT = "/api-admin/v1/rankings/weights"
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

    private fun adminHeaders(): HttpHeaders = HttpHeaders().apply { set("X-Loopers-Ldap", "loopers.admin") }

    private fun post(path: String, body: Any? = null, headers: HttpHeaders = adminHeaders()): ResponseEntity<ApiResponse<Any>> =
        testRestTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headers), object : ParameterizedTypeReference<ApiResponse<Any>>() {})

    private fun registerRequest(version: String = "v2") = mapOf(
        "version" to version,
        "viewWeight" to 2,
        "likeWeight" to 8,
        "orderWeight" to 40,
    )

    @DisplayName("가중치 등록/전이 - ")
    @Nested
    inner class RegisterAndTransition {

        @DisplayName("등록하면 PREPARING으로 저장되고(논리 가중치 그대로 응답) boards KV에 버전이 실린다.")
        @Test
        fun registers_andSyncsBoardsKv() {
            val response = post(WEIGHTS_ENDPOINT, registerRequest())

            val data = response.body?.data as? Map<*, *>
            val boardsJson = redis.opsForValue().get("ranking:weights:boards")
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(data?.get("version")).isEqualTo("v2") },
                { assertThat((data?.get("viewWeight") as? Number)?.toLong()).isEqualTo(2L) },
                { assertThat(data?.get("status")).isEqualTo("PREPARING") },
                { assertThat(boardsJson).contains(""""version":"v2"""") },
                // 저장은 ×10 스케일 - collector가 읽는 boards KV에는 20/80/400이 실린다
                { assertThat(boardsJson).contains(""""VIEW":20""") },
                { assertThat(redis.opsForValue().get("ranking:weights:active")).isNull() },
            )
        }

        @DisplayName("중복 버전 등록은 409, 가중치 0 이하는 400, 잘못된 버전 형식은 400.")
        @Test
        fun validatesRegistration() {
            post(WEIGHTS_ENDPOINT, registerRequest())

            assertAll(
                { assertThat(post(WEIGHTS_ENDPOINT, registerRequest()).statusCode).isEqualTo(HttpStatus.CONFLICT) },
                {
                    assertThat(post(WEIGHTS_ENDPOINT, mapOf("version" to "v3", "viewWeight" to 0, "likeWeight" to 5, "orderWeight" to 50)).statusCode)
                        .isEqualTo(HttpStatus.BAD_REQUEST)
                },
                {
                    assertThat(post(WEIGHTS_ENDPOINT, mapOf("version" to "latest", "viewWeight" to 1, "likeWeight" to 5, "orderWeight" to 50)).statusCode)
                        .isEqualTo(HttpStatus.BAD_REQUEST)
                },
            )
        }

        @DisplayName("어드민 헤더가 없으면 403이다.")
        @Test
        fun returns403_withoutAdminHeader() {
            val response = post(WEIGHTS_ENDPOINT, registerRequest(), headers = HttpHeaders())

            assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        }

        @DisplayName("ACTIVE 버전 은퇴는 409 - 먼저 다른 버전으로 flip해야 한다.")
        @Test
        fun cannotRetireActiveVersion() {
            post(WEIGHTS_ENDPOINT, registerRequest("v1"))
            post("$WEIGHTS_ENDPOINT/v1/activate")

            val response = post("$WEIGHTS_ENDPOINT/v1/retire")

            assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        }
    }

    @DisplayName("버전 전환(flip)과 서빙 - ")
    @Nested
    inner class FlipAndServing {

        private fun markRolloverDone(version: String) {
            redis.opsForValue().set("ranking:rollover:status:$version:${today.format(DateTimeFormatter.BASIC_ISO_DATE)}", "DONE")
        }

        private fun getRankings(): Map<*, *>? {
            val response = testRestTemplate.exchange(
                "$RANKING_ENDPOINT?date=${today.format(DateTimeFormatter.BASIC_ISO_DATE)}",
                HttpMethod.GET,
                HttpEntity<Any>(HttpHeaders()),
                object : ParameterizedTypeReference<ApiResponse<Any>>() {},
            )
            return response.body?.data as? Map<*, *>
        }

        private fun firstProductId(data: Map<*, *>?): Long? {
            val firstItem = (data?.get("items") as? List<*>)?.firstOrNull() as? Map<*, *>
            return (firstItem?.get("productId") as? Number)?.toLong()
        }

        @DisplayName("v2 활성화(flip) 후 랭킹 조회는 v2 보드 순위로 응답하고, v1로 되돌리면 즉시 v1 순위로 롤백된다.")
        @Test
        fun servesActiveVersionBoard_andRollsBackInstantly() {
            // v1/v2 등록 + 각 보드에 서로 다른 순위 시드 (v1 1등: 101, v2 1등: 202)
            post(WEIGHTS_ENDPOINT, registerRequest("v1"))
            post(WEIGHTS_ENDPOINT, registerRequest("v2"))
            post("$WEIGHTS_ENDPOINT/v1/activate")
            markRolloverDone("v1")
            markRolloverDone("v2")
            redis.opsForZSet().add(RankingBoard.allOf("v1", today).key(), "101", 500.0)
            redis.opsForZSet().add(RankingBoard.allOf("v2", today).key(), "202", 800.0)

            val v1Serving = getRankings()

            post("$WEIGHTS_ENDPOINT/v2/activate")
            val v2Serving = getRankings()

            post("$WEIGHTS_ENDPOINT/v1/activate")
            val rolledBack = getRankings()

            assertAll(
                { assertThat(firstProductId(v1Serving)).isEqualTo(101L) },
                { assertThat(redis.opsForValue().get("ranking:weights:active")).isEqualTo("v1") },
                { assertThat(firstProductId(v2Serving)).isEqualTo(202L) },
                { assertThat(firstProductId(rolledBack)).isEqualTo(101L) },
            )
        }

        @DisplayName("active KV가 없으면 v1 간주로 서빙한다.")
        @Test
        fun defaultsToV1_whenActiveKvMissing() {
            markRolloverDone("v1")
            redis.opsForZSet().add(RankingBoard.allOf("v1", today).key(), "101", 500.0)

            val data = getRankings()

            assertThat(firstProductId(data)).isEqualTo(101L)
        }
    }
}
