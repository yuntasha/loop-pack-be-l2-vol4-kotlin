package com.loopers.domain.waitingqueue.model

/**
 * 토픽별 대기열 설정. 원본은 DB, hot path 는 Redis 캐시(어댑터가 은닉).
 *
 * @property pollingIntervalMs 스케줄러 승격 틱 주기
 * @property admitCountPerPoll 1회 승격 인원
 * @property admitWindowSec 승격 마커 TTL — 승격 후 입장 토큰을 발급받을 시간
 * @property accessTokenTtlSec 입장 토큰 TTL — 발급 후 보호 API 를 호출할 시간
 */
data class QueueConfig(
    val pollingIntervalMs: Long,
    val admitCountPerPoll: Int,
    val admitWindowSec: Int,
    val accessTokenTtlSec: Int,
) {
    fun merge(patch: QueueConfigPatch): QueueConfig = QueueConfig(
        pollingIntervalMs = patch.pollingIntervalMs ?: pollingIntervalMs,
        admitCountPerPoll = patch.admitCountPerPoll ?: admitCountPerPoll,
        admitWindowSec = patch.admitWindowSec ?: admitWindowSec,
        accessTokenTtlSec = patch.accessTokenTtlSec ?: accessTokenTtlSec,
    )

    companion object {
        fun default(): QueueConfig = QueueConfig(
            pollingIntervalMs = 3_000L,
            admitCountPerPoll = 100,
            admitWindowSec = 10,
            accessTokenTtlSec = 30,
        )
    }
}

/** 부분 수정용 패치. null 인 필드는 기존 값을 유지한다. */
data class QueueConfigPatch(
    val pollingIntervalMs: Long? = null,
    val admitCountPerPoll: Int? = null,
    val admitWindowSec: Int? = null,
    val accessTokenTtlSec: Int? = null,
)
