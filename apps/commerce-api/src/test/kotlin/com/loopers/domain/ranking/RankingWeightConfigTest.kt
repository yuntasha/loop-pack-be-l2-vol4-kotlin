package com.loopers.domain.ranking

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RankingWeightConfigTest {

    private fun preparing(version: String = "v2") = RankingWeightConfig.create(version, 2L, 8L, 40L)

    @DisplayName("생성 - ")
    @Nested
    inner class Create {
        @DisplayName("논리 가중치를 ×10 저장 스케일로 변환해 PREPARING 상태로 생성한다.")
        @Test
        fun createsWithStorageScale() {
            val config = RankingWeightConfig.create("v2", 2L, 8L, 40L)

            assertThat(config.viewWeight).isEqualTo(20L)
            assertThat(config.likeWeight).isEqualTo(80L)
            assertThat(config.orderWeight).isEqualTo(400L)
            assertThat(config.status).isEqualTo(RankingWeightStatus.PREPARING)
            assertThat(config.activatedAt).isNull()
        }

        @DisplayName("버전이 v{숫자} 형식이 아니면 BAD_REQUEST 예외가 발생한다.")
        @Test
        fun throws_whenInvalidVersionFormat() {
            listOf("latest", "V2", "v", "2", "v2a").forEach { invalid ->
                assertThatThrownBy { RankingWeightConfig.create(invalid, 1L, 5L, 50L) }
                    .isInstanceOf(CoreException::class.java)
                    .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
            }
        }

        @DisplayName("가중치가 0 이하면 BAD_REQUEST 예외가 발생한다.")
        @Test
        fun throws_whenNonPositiveWeight() {
            assertThatThrownBy { RankingWeightConfig.create("v2", 0L, 5L, 50L) }
                .isInstanceOf(CoreException::class.java)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
        }
    }

    @DisplayName("상태 전이 - ")
    @Nested
    inner class Transition {
        @DisplayName("PREPARING → activate() → ACTIVE, activatedAt이 기록된다.")
        @Test
        fun activates_fromPreparing() {
            val config = preparing()

            config.activate()

            assertThat(config.status).isEqualTo(RankingWeightStatus.ACTIVE)
            assertThat(config.activatedAt).isNotNull()
        }

        @DisplayName("ACTIVE → demote() → PREPARING (flip 시 기존 활성 버전 강등).")
        @Test
        fun demotes_fromActive() {
            val config = preparing().apply { activate() }

            config.demote()

            assertThat(config.status).isEqualTo(RankingWeightStatus.PREPARING)
        }

        @DisplayName("PREPARING → retire() → RETIRED.")
        @Test
        fun retires_fromPreparing() {
            val config = preparing()

            config.retire()

            assertThat(config.status).isEqualTo(RankingWeightStatus.RETIRED)
        }

        @DisplayName("RETIRED → reopen() → PREPARING (은퇴 번복).")
        @Test
        fun reopens_fromRetired() {
            val config = preparing().apply { retire() }

            config.reopen()

            assertThat(config.status).isEqualTo(RankingWeightStatus.PREPARING)
        }

        @DisplayName("서빙 중(ACTIVE)인 버전은 은퇴할 수 없다 - CONFLICT.")
        @Test
        fun cannotRetire_whenActive() {
            val config = preparing().apply { activate() }

            assertThatThrownBy { config.retire() }
                .isInstanceOf(CoreException::class.java)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
        }

        @DisplayName("RETIRED 버전은 바로 활성화할 수 없다(보드 공백) - CONFLICT. reopen부터 거쳐야 한다.")
        @Test
        fun cannotActivate_whenRetired() {
            val config = preparing().apply { retire() }

            assertThatThrownBy { config.activate() }
                .isInstanceOf(CoreException::class.java)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
        }

        @DisplayName("이미 ACTIVE인 버전을 다시 활성화하면 CONFLICT.")
        @Test
        fun cannotActivate_whenAlreadyActive() {
            val config = preparing().apply { activate() }

            assertThatThrownBy { config.activate() }
                .isInstanceOf(CoreException::class.java)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
        }
    }
}
