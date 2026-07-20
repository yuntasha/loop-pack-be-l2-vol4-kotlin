package com.loopers.batch.job.ranking

import com.loopers.batch.job.ranking.step.RankingWeightReplayTasklet
import com.loopers.batch.listener.JobListener
import com.loopers.batch.listener.StepMonitorListener
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.support.transaction.ResourcelessTransactionManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 가중치 재계산(replay) 배치. 신규 가중치 버전 등록 후 실행 —
 * outbox의 어제~컷오프 T 이벤트를 신 버전 가중치로 재계산해 오늘 보드를 병행 구축하고,
 * 완료 시 활성 버전 포인터를 flip한다. 최초 무버전 키 → v1 마이그레이션에도 그대로 쓰인다.
 */
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = RankingWeightReplayJobConfig.JOB_NAME)
@Configuration
class RankingWeightReplayJobConfig(
    private val jobRepository: JobRepository,
    private val jobListener: JobListener,
    private val stepMonitorListener: StepMonitorListener,
    private val rankingWeightReplayTasklet: RankingWeightReplayTasklet,
) {
    companion object {
        const val JOB_NAME = "rankingWeightReplayJob"
        private const val STEP_REPLAY_NAME = "rankingWeightReplayStep"
    }

    @Bean(JOB_NAME)
    fun rankingWeightReplayJob(): Job {
        return JobBuilder(JOB_NAME, jobRepository)
            .incrementer(RunIdIncrementer())
            .start(rankingWeightReplayStep())
            .listener(jobListener)
            .build()
    }

    @JobScope
    @Bean(STEP_REPLAY_NAME)
    fun rankingWeightReplayStep(): Step {
        return StepBuilder(STEP_REPLAY_NAME, jobRepository)
            .tasklet(rankingWeightReplayTasklet, ResourcelessTransactionManager())
            .listener(stepMonitorListener)
            .build()
    }
}
