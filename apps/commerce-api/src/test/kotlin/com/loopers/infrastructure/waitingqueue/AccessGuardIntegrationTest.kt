package com.loopers.infrastructure.waitingqueue

import com.loopers.application.waitingqueue.EnterCommand
import com.loopers.application.waitingqueue.IssueTokenCommand
import com.loopers.application.waitingqueue.VerifyCommand
import com.loopers.interfaces.api.waitingqueue.QueueAdmissionApplicationServicePort
import com.loopers.interfaces.api.waitingqueue.QueueApplicationServicePort
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class AccessGuardIntegrationTest @Autowired constructor(
    private val queueApplicationService: QueueApplicationServicePort,
    private val admissionService: QueueAdmissionApplicationServicePort,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    @DisplayName("발급된 입장 토큰은 검증을 통과한다.")
    @Test
    fun verifiesIssuedToken() {
        val enter = queueApplicationService.enter(EnterCommand("order", 1L))
        admissionService.admitDueTopics(System.currentTimeMillis())
        val issued = queueApplicationService.issueAccessToken(IssueTokenCommand(enter.waitToken))

        val verified = queueApplicationService.verifyAccess(VerifyCommand(issued.accessToken, "order"))

        assertThat(verified).isTrue()
    }

    @DisplayName("위조 토큰은 검증에 실패한다.")
    @Test
    fun rejectsForgedToken() {
        val verified = queueApplicationService.verifyAccess(VerifyCommand("at.forged.signature", "order"))

        assertThat(verified).isFalse()
    }
}
