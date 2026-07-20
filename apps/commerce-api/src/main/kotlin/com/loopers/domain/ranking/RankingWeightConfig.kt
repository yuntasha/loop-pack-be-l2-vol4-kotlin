package com.loopers.domain.ranking

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import java.time.ZonedDateTime

/**
 * 버전별 랭킹 가중치 설정 (SoT: MySQL). 가중치는 논리값에 ×10한 저장 스케일로 보관한다 —
 * 이월(carry-over) 계수 0.1을 곱해도 점수가 정수를 유지해야 한다. Redis KV는 이 설정의 전파용 캐시다.
 */
class RankingWeightConfig(
    val version: String,
    val viewWeight: Long,
    val likeWeight: Long,
    val orderWeight: Long,
    status: RankingWeightStatus,
    val createdAt: ZonedDateTime,
    activatedAt: ZonedDateTime? = null,
) {
    var status: RankingWeightStatus = status
        private set

    var activatedAt: ZonedDateTime? = activatedAt
        private set

    init {
        if (!version.matches(VERSION_PATTERN)) {
            throw CoreException(ErrorType.BAD_REQUEST, "version은 v{숫자} 형식이어야 합니다. version=$version")
        }
        if (viewWeight <= 0 || likeWeight <= 0 || orderWeight <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "가중치는 양수여야 합니다.")
        }
    }

    /** PREPARING → ACTIVE. replay 완료(flip) 또는 롤백 시 호출된다. RETIRED는 보드가 이미 비어 있을 수 있어 재개(reopen)부터 거쳐야 한다. */
    fun activate() {
        if (status != RankingWeightStatus.PREPARING) {
            throw CoreException(ErrorType.CONFLICT, "PREPARING 상태만 활성화할 수 있습니다. version=$version, status=$status")
        }
        status = RankingWeightStatus.ACTIVE
        activatedAt = ZonedDateTime.now()
    }

    /** ACTIVE → PREPARING. 다른 버전으로 flip될 때 강등된다 — 롤백 대비 이중 적재는 계속된다. */
    fun demote() {
        if (status != RankingWeightStatus.ACTIVE) {
            throw CoreException(ErrorType.CONFLICT, "ACTIVE 상태만 강등할 수 있습니다. version=$version, status=$status")
        }
        status = RankingWeightStatus.PREPARING
    }

    /** PREPARING → RETIRED. 서빙 중(ACTIVE)인 버전은 은퇴할 수 없다. */
    fun retire() {
        if (status != RankingWeightStatus.PREPARING) {
            throw CoreException(ErrorType.CONFLICT, "서빙 중이 아닌 PREPARING 상태만 은퇴할 수 있습니다. version=$version, status=$status")
        }
        status = RankingWeightStatus.RETIRED
    }

    /** RETIRED → PREPARING. 은퇴 번복 — 보드 공백은 replay 재실행으로 복구해야 한다. */
    fun reopen() {
        if (status != RankingWeightStatus.RETIRED) {
            throw CoreException(ErrorType.CONFLICT, "RETIRED 상태만 재개할 수 있습니다. version=$version, status=$status")
        }
        status = RankingWeightStatus.PREPARING
    }

    companion object {
        private val VERSION_PATTERN = Regex("^v[0-9]+$")
        private const val STORAGE_SCALE = 10L

        /** admin이 입력한 논리 가중치(예: VIEW 2)를 ×10 저장 스케일로 변환해 생성한다. */
        fun create(version: String, viewWeight: Long, likeWeight: Long, orderWeight: Long): RankingWeightConfig =
            RankingWeightConfig(
                version = version,
                viewWeight = viewWeight * STORAGE_SCALE,
                likeWeight = likeWeight * STORAGE_SCALE,
                orderWeight = orderWeight * STORAGE_SCALE,
                status = RankingWeightStatus.PREPARING,
                createdAt = ZonedDateTime.now(),
            )
    }
}
