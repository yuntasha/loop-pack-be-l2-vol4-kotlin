package com.loopers.application.waitingqueue

import com.loopers.domain.waitingqueue.model.AdmitSummary

/** 승격 처리 요약 결과. */
data class AdmitSummaryResult(
    val topicsProcessed: Int,
    val totalAdmitted: Int,
) {
    companion object {
        fun from(summary: AdmitSummary): AdmitSummaryResult = AdmitSummaryResult(
            topicsProcessed = summary.topicsProcessed,
            totalAdmitted = summary.totalAdmitted,
        )
    }
}
