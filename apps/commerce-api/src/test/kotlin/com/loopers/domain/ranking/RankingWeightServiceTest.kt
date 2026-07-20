package com.loopers.domain.ranking

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RankingWeightServiceTest {

    private lateinit var configRepositoryPort: RankingWeightConfigRepositoryPort
    private lateinit var kvPort: RankingWeightKvPort
    private lateinit var service: RankingWeightService

    @BeforeEach
    fun setUp() {
        configRepositoryPort = mockk()
        kvPort = mockk(relaxUnitFun = true)
        service = RankingWeightService(configRepositoryPort, kvPort)

        every { configRepositoryPort.save(any()) } answers { firstArg() }
    }

    private fun v1Active() = RankingWeightConfig.create("v1", 1L, 5L, 50L).apply { activate() }

    private fun v2Preparing() = RankingWeightConfig.create("v2", 2L, 8L, 40L)

    @DisplayName("등록하면 PREPARING으로 저장되고 boards KV가 재구성된다 (RETIRED 제외).")
    @Test
    fun registers_andSyncsBoards() {
        val v1 = v1Active()
        val retired = RankingWeightConfig.create("v0", 1L, 1L, 1L).apply { retire() }
        every { configRepositoryPort.findByVersion("v2") } returns null
        every { configRepositoryPort.findAll() } answers { listOf(retired, v1, v2Preparing()) }
        every { configRepositoryPort.findActive() } returns v1

        val result = service.register("v2", 2L, 8L, 40L)

        assertThat(result.status).isEqualTo(RankingWeightStatus.PREPARING)
        val boards = slot<List<RankingWeightConfig>>()
        verify(exactly = 1) { kvPort.syncBoards(capture(boards)) }
        assertThat(boards.captured.map { it.version }).containsExactly("v1", "v2")
        verify(exactly = 1) { kvPort.setActive("v1") }
    }

    @DisplayName("이미 등록된 버전을 다시 등록하면 CONFLICT.")
    @Test
    fun throwsConflict_whenVersionDuplicated() {
        every { configRepositoryPort.findByVersion("v2") } returns v2Preparing()

        assertThatThrownBy { service.register("v2", 2L, 8L, 40L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("활성화(flip)하면 기존 ACTIVE는 PREPARING으로 강등되고 active KV가 새 버전으로 flip된다.")
    @Test
    fun activate_demotesCurrentAndFlipsKv() {
        val v1 = v1Active()
        val v2 = v2Preparing()
        every { configRepositoryPort.findByVersion("v2") } returns v2
        every { configRepositoryPort.findActive() } returns v1
        every { configRepositoryPort.findAll() } answers { listOf(v1, v2) }

        val result = service.activate("v2")

        assertThat(v1.status).isEqualTo(RankingWeightStatus.PREPARING)
        assertThat(result.status).isEqualTo(RankingWeightStatus.ACTIVE)
        verify(exactly = 1) { kvPort.setActive("v2") }
    }

    @DisplayName("미등록 버전을 활성화하면 NOT_FOUND.")
    @Test
    fun throwsNotFound_whenActivatingUnknownVersion() {
        every { configRepositoryPort.findByVersion("v99") } returns null

        assertThatThrownBy { service.activate("v99") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("은퇴하면 boards KV에서 제외된다 (이중 적재 종료).")
    @Test
    fun retire_removesFromBoards() {
        val v1 = RankingWeightConfig.create("v1", 1L, 5L, 50L)
        val v2 = v2Preparing().apply { activate() }
        every { configRepositoryPort.findByVersion("v1") } returns v1
        every { configRepositoryPort.findAll() } answers { listOf(v1, v2) }

        service.retire("v1")

        val boards = slot<List<RankingWeightConfig>>()
        verify { kvPort.syncBoards(capture(boards)) }
        assertThat(boards.captured.map { it.version }).containsExactly("v2")
    }
}
