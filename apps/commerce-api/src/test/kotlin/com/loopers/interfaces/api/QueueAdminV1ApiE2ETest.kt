package com.loopers.interfaces.api

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueueAdminV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    private fun configEndpoint(topic: String) = "/api-admin/v1/queue/$topic/config"

    private fun headers(ldap: String?): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        ldap?.let { set("X-Loopers-Ldap", it) }
    }

    private val responseType = object : ParameterizedTypeReference<ApiResponse<Map<String, Any?>>>() {}

    private fun putConfig(topic: String, body: Map<String, Any?>, ldap: String?): ResponseEntity<ApiResponse<Map<String, Any?>>> =
        testRestTemplate.exchange(configEndpoint(topic), HttpMethod.PUT, HttpEntity(body, headers(ldap)), responseType)

    private fun getConfig(topic: String, ldap: String?): ResponseEntity<ApiResponse<Map<String, Any?>>> =
        testRestTemplate.exchange(configEndpoint(topic), HttpMethod.GET, HttpEntity<Any>(headers(ldap)), responseType)

    @DisplayName("관리자가 설정을 변경하면, 200 과 변경된 값을 반환하고 조회에도 반영된다.")
    @Test
    fun updatesConfig() {
        val putResponse = putConfig("order", mapOf("admitCountPerPoll" to 7), "loopers.admin")
        val getResponse = getConfig("order", "loopers.admin")

        assertAll(
            { assertThat(putResponse.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat((putResponse.body?.data?.get("admitCountPerPoll") as? Number)?.toInt()).isEqualTo(7) },
            { assertThat((getResponse.body?.data?.get("admitCountPerPoll") as? Number)?.toInt()).isEqualTo(7) },
        )
    }

    @DisplayName("설정이 없는 토픽을 조회하면, 기본값을 반환한다.")
    @Test
    fun returnsDefaultForUnsetTopic() {
        val response = getConfig("coupon", "loopers.admin")

        assertAll(
            { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat((response.body?.data?.get("admitCountPerPoll") as? Number)?.toInt()).isEqualTo(100) },
            { assertThat((response.body?.data?.get("pollingIntervalMs") as? Number)?.toLong()).isEqualTo(3_000L) },
        )
    }

    @DisplayName("관리자 권한 없이 설정을 변경하면, 403 응답을 받는다.")
    @Test
    fun forbidsWithoutAdmin() {
        val response = putConfig("order", mapOf("admitCountPerPoll" to 7), null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }
}
