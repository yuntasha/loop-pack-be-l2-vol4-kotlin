package com.loopers.batch.job.ranking

import com.loopers.batch.job.ranking.step.RankingRolloverTasklet
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
 * 랭킹 이월(carry-over) 배치. 매일 23:50 실행 —
 * snapshot:{D}의 각 점수를 floor(×0.1)해 ranking:all:{D+1}, ranking:snapshot:{D+1}에 심는다.
 */
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = RankingRolloverJobConfig.JOB_NAME)
@Configuration
class RankingRolloverJobConfig(
    private val jobRepository: JobRepository,
    private val jobListener: JobListener,
    private val stepMonitorListener: StepMonitorListener,
    private val rankingRolloverTasklet: RankingRolloverTasklet,
) {
    companion object {
        const val JOB_NAME = "rankingRolloverJob"
        private const val STEP_ROLLOVER_NAME = "rankingRolloverStep"
    }

    @Bean(JOB_NAME)
    fun rankingRolloverJob(): Job {
        return JobBuilder(JOB_NAME, jobRepository)
            .incrementer(RunIdIncrementer())
            .start(rankingRolloverStep())
            .listener(jobListener)
            .build()
    }

    @JobScope
    @Bean(STEP_ROLLOVER_NAME)
    fun rankingRolloverStep(): Step {
        return StepBuilder(STEP_ROLLOVER_NAME, jobRepository)
            .tasklet(rankingRolloverTasklet, ResourcelessTransactionManager())
            .listener(stepMonitorListener)
            .build()
    }
}
