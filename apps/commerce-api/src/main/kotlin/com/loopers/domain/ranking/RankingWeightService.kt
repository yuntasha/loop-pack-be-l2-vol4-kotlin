package com.loopers.domain.ranking

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 가중치 설정 Domain Service. 상태 전이(등록/활성화/은퇴/재개)와 그에 따른 KV 전파를 오케스트레이션한다.
 * 상태를 전이시킬 때마다 boards/active KV를 테이블 전체 기준으로 재구성한다(멱등).
 * 트랜잭션 경계는 application 계층이 가진다.
 */
class RankingWeightService(
    private val rankingWeightConfigRepositoryPort: RankingWeightConfigRepositoryPort,
    private val rankingWeightKvPort: RankingWeightKvPort,
) {
    fun getAll(): List<RankingWeightConfig> = rankingWeightConfigRepositoryPort.findAll()

    /** 신규 버전 등록(PREPARING). boards KV에 실려 collector가 이중 적재를 시작한다. */
    fun register(version: String, viewWeight: Long, likeWeight: Long, orderWeight: Long): RankingWeightConfig {
        if (rankingWeightConfigRepositoryPort.findByVersion(version) != null) {
            throw CoreException(ErrorType.CONFLICT, "이미 등록된 가중치 버전입니다. version=$version")
        }
        val config = rankingWeightConfigRepositoryPort.save(
            RankingWeightConfig.create(version, viewWeight, likeWeight, orderWeight),
        )
        syncKv()
        return config
    }

    /** 활성 버전 전환(flip/롤백). 기존 ACTIVE는 PREPARING으로 강등돼 병행 적재를 유지한다. */
    fun activate(version: String): RankingWeightConfig {
        val target = getByVersion(version)
        rankingWeightConfigRepositoryPort.findActive()
            ?.takeIf { it.version != version }
            ?.also { current ->
                current.demote()
                rankingWeightConfigRepositoryPort.save(current)
            }
        target.activate()
        val saved = rankingWeightConfigRepositoryPort.save(target)
        syncKv()
        return saved
    }

    /** 은퇴 — 이중 적재 종료. 보드 키는 TTL로 자연 소멸한다. */
    fun retire(version: String): RankingWeightConfig {
        val target = getByVersion(version)
        target.retire()
        val saved = rankingWeightConfigRepositoryPort.save(target)
        syncKv()
        return saved
    }

    /** 은퇴 번복 — 다시 적재 대상에 올린다. 은퇴 기간의 보드 공백은 replay 재실행으로 복구해야 한다. */
    fun reopen(version: String): RankingWeightConfig {
        val target = getByVersion(version)
        target.reopen()
        val saved = rankingWeightConfigRepositoryPort.save(target)
        syncKv()
        return saved
    }

    private fun getByVersion(version: String): RankingWeightConfig =
        rankingWeightConfigRepositoryPort.findByVersion(version)
            ?: throw CoreException(ErrorType.NOT_FOUND, "가중치 버전을 찾을 수 없습니다. version=$version")

    private fun syncKv() {
        val configs = rankingWeightConfigRepositoryPort.findAll()
        rankingWeightKvPort.syncBoards(configs.filter { it.status != RankingWeightStatus.RETIRED })
        configs.firstOrNull { it.status == RankingWeightStatus.ACTIVE }
            ?.also { rankingWeightKvPort.setActive(it.version) }
    }
}
