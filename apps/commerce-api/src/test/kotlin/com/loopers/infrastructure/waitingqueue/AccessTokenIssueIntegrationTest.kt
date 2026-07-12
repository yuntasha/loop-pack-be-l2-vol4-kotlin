package com.loopers.infrastructure.waitingqueue

import com.loopers.application.waitingqueue.EnterCommand
import com.loopers.application.waitingqueue.IssueTokenCommand
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.waitingqueue.model.QueueTopic
import com.loopers.interfaces.api.waitingqueue.QueueAdmissionApplicationServicePort
import com.loopers.interfaces.api.waitingqueue.QueueApplicationServicePort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate

@Suppress("UNCHECKED_CAST")
@SpringBootTest
class AccessTokenIssueIntegrationTest @Autowired constructor(
    private val queueApplicationService: QueueApplicationServicePort,
    private val admissionService: QueueAdmissionApplicationServicePort,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    private val redis = masterTemplate as RedisTemplate<String, String>
    private val topic = QueueTopic("order")

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    @DisplayName("승격 후 입장 토큰을 발급하면, Redis access 키에 동일 토큰이 저장된다.")
    @Test
    fun issuesAndStoresAccessKey() {
        val enter = queueApplicationService.enter(EnterCommand("order", 1L))
        admissionService.admitDueTopics(System.currentTimeMillis())

        val result = queueApplicationService.issueAccessToken(IssueTokenCommand(enter.waitToken))

        assertThat(result.accessToken).startsWith("at.")
        assertThat(redis.opsForValue().get(RedisAccessTokenStore.accessKey(topic, 1L))).isEqualTo(result.accessToken)
    }

    @DisplayName("승격되지 않은 상태에서 발급하면, CONFLICT 예외(NOT_ADMITTED).")
    @Test
    fun conflictWhenNotAdmitted() {
        val enter = queueApplicationService.enter(EnterCommand("order", 1L))

        assertThatThrownBy { queueApplicationService.issueAccessToken(IssueTokenCommand(enter.waitToken)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }
}
