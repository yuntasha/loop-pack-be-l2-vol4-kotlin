package com.loopers.interfaces.api

import com.loopers.application.user.SignupCommand
import com.loopers.interfaces.api.user.UserApplicationServicePort
import com.loopers.interfaces.api.waitingqueue.QueueAdmissionApplicationServicePort
import com.loopers.interfaces.api.waitingqueue.QueueProtectedTestController
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.time.LocalDate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WaitingQueueV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val userApplicationService: UserApplicationServicePort,
    private val admissionService: QueueAdmissionApplicationServicePort,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    private fun signup(loginId: String = "tester01", pw: String = "password1234") {
        userApplicationService.signup(
            SignupCommand(
                loginId = loginId,
                rawPassword = pw,
                name = "테스터",
                birth = LocalDate.of(2000, 1, 1),
                email = "$loginId@example.com",
            ),
        )
    }

    private fun authHeaders(loginId: String?, loginPw: String?): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        loginId?.let { set("X-Loopers-LoginId", it) }
        loginPw?.let { set("X-Loopers-LoginPw", it) }
    }

    private fun callProtected(loginId: String?, loginPw: String?): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val responseType = object : ParameterizedTypeReference<ApiResponse<Map<String, Any?>>>() {}
        return testRestTemplate.exchange(
            QueueProtectedTestController.PATH,
            HttpMethod.GET,
            HttpEntity<Any>(authHeaders(loginId, loginPw)),
            responseType,
        )
    }

    @DisplayName("@WaitingQueue 보호 API 를 입장 토큰 없이 호출하면, 429 와 대기열 토큰을 받는다.")
    @Test
    fun returnsTooManyRequestsWithWaitToken() {
        signup()

        val response = callProtected("tester01", "password1234")

        assertAll(
            { assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS) },
            { assertThat(response.body?.data?.get("topic")).isEqualTo("order") },
            { assertThat(response.body?.data?.get("waitToken") as? String).startsWith("wq.") },
        )
    }

    @DisplayName("보호 API 를 다시 호출하면, 매번 새 대기열 토큰이 발급된다(맨 뒤 재진입).")
    @Test
    fun reissuesWaitTokenOnEachCall() {
        signup()

        val first = callProtected("tester01", "password1234")
        val second = callProtected("tester01", "password1234")

        assertAll(
            { assertThat(first.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS) },
            { assertThat(second.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS) },
            { assertThat(second.body?.data?.get("waitToken") as? String).startsWith("wq.") },
        )
    }

    @DisplayName("인증 헤더 없이 보호 API 를 호출하면, 401 응답을 받는다.")
    @Test
    fun returnsUnauthorizedWithoutAuth() {
        val response = callProtected(null, null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private fun waitTokenOf(loginId: String, pw: String): String =
        callProtected(loginId, pw).body?.data?.get("waitToken") as String

    private fun callPosition(waitToken: String): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val headers = HttpHeaders().apply { set("X-Queue-Wait-Token", waitToken) }
        val responseType = object : ParameterizedTypeReference<ApiResponse<Map<String, Any?>>>() {}
        return testRestTemplate.exchange(
            "/api/v1/queue/position",
            HttpMethod.GET,
            HttpEntity<Any>(headers),
            responseType,
        )
    }

    @DisplayName("대기열 토큰으로 순번을 조회하면, WAITING 과 1-based 순번을 반환한다.")
    @Test
    fun positionReturnsWaiting() {
        signup()
        val waitToken = waitTokenOf("tester01", "password1234")

        val response = callPosition(waitToken)

        assertAll(
            { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat(response.body?.data?.get("topic")).isEqualTo("order") },
            { assertThat(response.body?.data?.get("status")).isEqualTo("WAITING") },
            { assertThat((response.body?.data?.get("rank") as? Number)?.toLong()).isEqualTo(1L) },
        )
    }

    @DisplayName("위조된 대기열 토큰으로 순번을 조회하면, 401 응답을 받는다.")
    @Test
    fun positionRejectsForgedToken() {
        val response = callPosition("wq.forged.payload")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private fun callIssueToken(waitToken: String): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val headers = HttpHeaders().apply { set("X-Queue-Wait-Token", waitToken) }
        val responseType = object : ParameterizedTypeReference<ApiResponse<Map<String, Any?>>>() {}
        return testRestTemplate.exchange(
            "/api/v1/queue/token",
            HttpMethod.POST,
            HttpEntity<Any>(headers),
            responseType,
        )
    }

    @DisplayName("승격된 뒤 입장 토큰을 발급하면, 200 과 accessToken 을 받는다.")
    @Test
    fun issuesAccessTokenAfterAdmitted() {
        signup()
        val waitToken = waitTokenOf("tester01", "password1234")
        admissionService.admitDueTopics(System.currentTimeMillis())

        val response = callIssueToken(waitToken)

        assertAll(
            { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat(response.body?.data?.get("topic")).isEqualTo("order") },
            { assertThat(response.body?.data?.get("accessToken") as? String).startsWith("at.") },
        )
    }

    @DisplayName("승격되지 않은 상태에서 입장 토큰을 발급하면, 409 응답을 받는다.")
    @Test
    fun rejectsIssueWhenNotAdmitted() {
        signup()
        val waitToken = waitTokenOf("tester01", "password1234")

        val response = callIssueToken(waitToken)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    private fun callProtectedWithAccess(
        loginId: String?,
        loginPw: String?,
        accessToken: String,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val headers = authHeaders(loginId, loginPw).apply { set("X-Queue-Access-Token", accessToken) }
        val responseType = object : ParameterizedTypeReference<ApiResponse<Map<String, Any?>>>() {}
        return testRestTemplate.exchange(
            QueueProtectedTestController.PATH,
            HttpMethod.GET,
            HttpEntity<Any>(headers),
            responseType,
        )
    }

    @DisplayName("전체 사이클: 진입 → 승격 → 토큰 발급 → 입장 토큰으로 보호 API 를 호출하면 200.")
    @Test
    fun fullCyclePasses() {
        signup()
        val waitToken = waitTokenOf("tester01", "password1234")
        admissionService.admitDueTopics(System.currentTimeMillis())
        val accessToken = callIssueToken(waitToken).body?.data?.get("accessToken") as String

        val response = callProtectedWithAccess("tester01", "password1234", accessToken)

        assertAll(
            { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat((response.body?.data?.get("userId") as? Number)).isNotNull() },
        )
    }

    @DisplayName("위조된 입장 토큰으로 보호 API 를 호출하면, 통과하지 못하고 429 로 재진입한다.")
    @Test
    fun forgedAccessTokenReenters() {
        signup()

        val response = callProtectedWithAccess("tester01", "password1234", "at.forged.signature")

        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }
}
