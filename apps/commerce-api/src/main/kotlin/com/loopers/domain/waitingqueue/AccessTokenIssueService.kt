package com.loopers.domain.waitingqueue

import com.loopers.domain.waitingqueue.model.AccessToken
import com.loopers.domain.waitingqueue.model.WaitToken
import com.loopers.domain.waitingqueue.port.AccessTokenStorePort
import com.loopers.domain.waitingqueue.port.AdmissionMarkerPort
import com.loopers.domain.waitingqueue.port.QueueConfigPort
import com.loopers.domain.waitingqueue.port.TokenSignerPort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 입장 토큰 발급(UC3). 승격 마커가 있어야 발급하며, 발급한 토큰을 Redis access 키로 백업한다.
 * 마커가 없으면(아직/이미 만료) 발급 불가.
 */
class AccessTokenIssueService(
    private val marker: AdmissionMarkerPort,
    private val accessStore: AccessTokenStorePort,
    private val config: QueueConfigPort,
    private val signer: TokenSignerPort,
) {
    fun issue(rawWaitToken: String, now: Long): AccessToken {
        val waitToken = WaitToken.parse(rawWaitToken, signer)

        if (!marker.exists(waitToken.topic, waitToken.userId)) {
            throw CoreException(ErrorType.CONFLICT, "아직 차례가 아니거나 승격 시간이 만료되었습니다.")
        }

        val cfg = config.get(waitToken.topic)
        val accessToken = AccessToken.issue(waitToken.topic, waitToken.userId, now, cfg.accessTokenTtlSec, signer)
        accessStore.store(waitToken.topic, waitToken.userId, accessToken.raw, cfg.accessTokenTtlSec)
        return accessToken
    }
}
