package com.loopers.application.waitingqueue

import com.loopers.domain.waitingqueue.AccessGuardService
import com.loopers.domain.waitingqueue.AccessTokenIssueService
import com.loopers.domain.waitingqueue.QueueAdmissionService
import com.loopers.domain.waitingqueue.QueueConfigService
import com.loopers.domain.waitingqueue.QueueEntryService
import com.loopers.domain.waitingqueue.QueuePositionService
import com.loopers.domain.waitingqueue.port.AccessTokenStorePort
import com.loopers.domain.waitingqueue.port.AdmissionGatePort
import com.loopers.domain.waitingqueue.port.AdmissionMarkerPort
import com.loopers.domain.waitingqueue.port.QueueConfigPort
import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.domain.waitingqueue.port.WaitingQueuePort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 대기열 도메인 서비스 빈 등록. 도메인 서비스는 Spring 의존성을 갖지 않으므로 여기서 조립한다.
 */
@Configuration
class WaitingQueueBeanConfig {
    @Bean
    fun queueEntryService(
        waitingQueuePort: WaitingQueuePort,
        tokenSignerPort: TokenSignerPort,
    ): QueueEntryService = QueueEntryService(waitingQueuePort, tokenSignerPort)

    @Bean
    fun queuePositionService(
        waitingQueuePort: WaitingQueuePort,
        admissionMarkerPort: AdmissionMarkerPort,
        queueConfigPort: QueueConfigPort,
        tokenSignerPort: TokenSignerPort,
    ): QueuePositionService = QueuePositionService(
        waitingQueuePort,
        admissionMarkerPort,
        queueConfigPort,
        tokenSignerPort,
    )

    @Bean
    fun queueAdmissionService(
        waitingQueuePort: WaitingQueuePort,
        admissionMarkerPort: AdmissionMarkerPort,
        queueConfigPort: QueueConfigPort,
        admissionGatePort: AdmissionGatePort,
    ): QueueAdmissionService = QueueAdmissionService(
        waitingQueuePort,
        admissionMarkerPort,
        queueConfigPort,
        admissionGatePort,
    )

    @Bean
    fun accessTokenIssueService(
        admissionMarkerPort: AdmissionMarkerPort,
        accessTokenStorePort: AccessTokenStorePort,
        queueConfigPort: QueueConfigPort,
        tokenSignerPort: TokenSignerPort,
    ): AccessTokenIssueService = AccessTokenIssueService(
        admissionMarkerPort,
        accessTokenStorePort,
        queueConfigPort,
        tokenSignerPort,
    )

    @Bean
    fun accessGuardService(
        accessTokenStorePort: AccessTokenStorePort,
        tokenSignerPort: TokenSignerPort,
    ): AccessGuardService = AccessGuardService(accessTokenStorePort, tokenSignerPort)

    @Bean
    fun queueConfigService(queueConfigPort: QueueConfigPort): QueueConfigService =
        QueueConfigService(queueConfigPort)
}
