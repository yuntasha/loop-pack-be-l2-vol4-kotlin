package com.loopers.application.waitingqueue

import com.loopers.interfaces.api.waitingqueue.QueueAdmissionApplicationServicePort
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 대기열 승격 마스터 틱. 짧은 주기로 돌며 각 토픽의 폴링 주기 경과 여부를 확인해 승격한다.
 * (단일 인스턴스 상주 가정. 다중 인스턴스로 띄우면 중복 승격이 발생할 수 있다.)
 * 테스트에서는 승격 서비스를 직접 호출해 검증하므로 자동 틱을 끈다(@Profile("!test")).
 */
@Profile("!test")
@Component
class QueueAdmissionScheduler(
    private val admissionService: QueueAdmissionApplicationServicePort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${waiting-queue.admission.tick-ms:1000}")
    fun tick() {
        val summary = admissionService.admitDueTopics(System.currentTimeMillis())
        if (summary.totalAdmitted > 0) {
            log.info("대기열 승격 완료: topicsProcessed={}, totalAdmitted={}", summary.topicsProcessed, summary.totalAdmitted)
        }
    }
}
