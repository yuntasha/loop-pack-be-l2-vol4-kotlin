package com.loopers.domain.waitingqueue.model

/** 한 번의 마스터 틱에서 처리된 승격 결과. */
data class AdmitSummary(
    val topicsProcessed: Int,
    val totalAdmitted: Int,
)
