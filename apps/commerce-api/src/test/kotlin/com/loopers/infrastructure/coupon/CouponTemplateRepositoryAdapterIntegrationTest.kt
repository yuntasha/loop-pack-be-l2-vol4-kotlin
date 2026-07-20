package com.loopers.infrastructure.coupon

import com.loopers.domain.common.PageRequest
import com.loopers.domain.coupon.CouponTemplate
import com.loopers.domain.coupon.CouponTemplateRepositoryPort
import com.loopers.domain.coupon.CouponType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDateTime

@SpringBootTest
class CouponTemplateRepositoryAdapterIntegrationTest @Autowired constructor(
    private val couponTemplateRepositoryPort: CouponTemplateRepositoryPort,
    private val couponTemplateJpaRepository: CouponTemplateJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    private val expiredAt = LocalDateTime.parse("2026-12-31T23:59:59")

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun template(
        name: String = "테스트 쿠폰",
        type: CouponType = CouponType.FIXED,
        value: Long = 1_000L,
        minOrderAmount: Long = 0L,
        totalCount: Long = 100L,
    ) = CouponTemplate.create(
        name = name,
        type = type,
        value = value,
        minOrderAmount = minOrderAmount,
        expiredAt = expiredAt,
        totalCount = totalCount,
    )

    @DisplayName("save를 호출할 때, ")
    @Nested
    inner class Save {
        @DisplayName("id가 0인 템플릿을 저장하면, id가 부여되고 DB에 INSERT된다.")
        @Test
        fun insertsTemplate_whenIdIsZero() {
            val saved = couponTemplateRepositoryPort.save(
                template(name = "1만원 할인", value = 10_000L, minOrderAmount = 30_000L),
            )

            assertThat(saved.id).isGreaterThan(0L)
            assertThat(couponTemplateJpaRepository.findById(saved.id)).isPresent
        }

        @DisplayName("저장한 템플릿을 다시 조회하면, 모든 필드가 동일하게 복원된다.")
        @Test
        fun restoresAllFields() {
            val saved = couponTemplateRepositoryPort.save(
                template(name = "10% 할인", type = CouponType.RATE, value = 10L, minOrderAmount = 10_000L, totalCount = 50L),
            )

            val found = couponTemplateRepositoryPort.findById(saved.id)

            assertThat(found).isNotNull
            assertThat(found?.name).isEqualTo("10% 할인")
            assertThat(found?.type).isEqualTo(CouponType.RATE)
            assertThat(found?.value).isEqualTo(10L)
            assertThat(found?.minOrderAmount).isEqualTo(10_000L)
            assertThat(found?.expiredAt).isEqualTo(expiredAt)
            assertThat(found?.totalCount).isEqualTo(50L)
        }

        @DisplayName("id가 있는 템플릿을 저장하면, 새 행이 아니라 기존 행이 UPDATE된다.")
        @Test
        fun updatesInPlace_whenIdExists() {
            val saved = couponTemplateRepositoryPort.save(template(name = "원본", value = 1_000L))
            val newExpiredAt = LocalDateTime.parse("2027-06-30T23:59:59")

            val updated = couponTemplateRepositoryPort.save(
                saved.update(
                    name = "변경",
                    type = CouponType.RATE,
                    value = 25L,
                    minOrderAmount = 50_000L,
                    expiredAt = newExpiredAt,
                ),
            )

            assertThat(updated.id).isEqualTo(saved.id)
            assertThat(couponTemplateJpaRepository.count()).isEqualTo(1L)
            val found = couponTemplateRepositoryPort.findById(saved.id)
            assertThat(found?.name).isEqualTo("변경")
            assertThat(found?.type).isEqualTo(CouponType.RATE)
            assertThat(found?.value).isEqualTo(25L)
            assertThat(found?.minOrderAmount).isEqualTo(50_000L)
            assertThat(found?.expiredAt).isEqualTo(newExpiredAt)
        }

        @DisplayName("존재하지 않는 id로 save하면, NOT_FOUND 예외가 발생한다.")
        @Test
        fun throwsNotFound_whenUpdatingMissingId() {
            val phantom = CouponTemplate(
                id = 9999L,
                name = "유령",
                type = CouponType.FIXED,
                value = 1_000L,
                minOrderAmount = 0L,
                expiredAt = expiredAt,
                totalCount = 100L,
            )

            org.junit.jupiter.api.assertThrows<com.loopers.support.error.CoreException> {
                couponTemplateRepositoryPort.save(phantom)
            }
        }
    }

    @DisplayName("findById를 호출할 때, ")
    @Nested
    inner class FindById {
        @DisplayName("존재하지 않는 id로 조회하면, null을 반환한다.")
        @Test
        fun returnsNull_whenMissing() {
            assertThat(couponTemplateRepositoryPort.findById(9999L)).isNull()
        }
    }

    @DisplayName("findByIds를 호출할 때, ")
    @Nested
    inner class FindByIds {
        @DisplayName("주어진 id 집합에 해당하는 템플릿만 반환한다(미존재 id는 무시).")
        @Test
        fun returnsMatchingTemplates() {
            val a = couponTemplateRepositoryPort.save(template(name = "A", value = 1_000L))
            val b = couponTemplateRepositoryPort.save(template(name = "B", value = 2_000L))

            val found = couponTemplateRepositoryPort.findByIds(setOf(a.id, b.id, 9999L))

            assertThat(found.map { it.id }).containsExactlyInAnyOrder(a.id, b.id)
        }

        @DisplayName("소프트 삭제된 템플릿은 결과에 포함되지 않는다.")
        @Test
        fun excludesSoftDeleted() {
            val a = couponTemplateRepositoryPort.save(template(name = "A", value = 1_000L))
            val deleted = couponTemplateRepositoryPort.save(template(name = "B", value = 2_000L))
            couponTemplateRepositoryPort.delete(deleted.id)

            val found = couponTemplateRepositoryPort.findByIds(setOf(a.id, deleted.id))

            assertThat(found.map { it.id }).containsExactly(a.id)
        }

        @DisplayName("빈 집합으로 호출하면 빈 목록을 반환한다.")
        @Test
        fun returnsEmpty_whenIdsEmpty() {
            assertThat(couponTemplateRepositoryPort.findByIds(emptySet())).isEmpty()
        }
    }

    @DisplayName("delete를 호출할 때, ")
    @Nested
    inner class Delete {
        @DisplayName("저장된 템플릿을 삭제하면, 1을 반환하고 소프트 삭제되어 더 이상 findById로 조회되지 않는다.")
        @Test
        fun softDeletesAndReturnsOne_whenExists() {
            val saved = couponTemplateRepositoryPort.save(template(name = "삭제 대상"))

            val deletedCount = couponTemplateRepositoryPort.delete(saved.id)

            assertThat(deletedCount).isEqualTo(1)
            assertThat(couponTemplateRepositoryPort.findById(saved.id)).isNull()
            assertThat(couponTemplateJpaRepository.findById(saved.id)).isEmpty
        }

        @DisplayName("존재하지 않는 id로 삭제하면, 0을 반환한다.")
        @Test
        fun returnsZero_whenMissing() {
            assertThat(couponTemplateRepositoryPort.delete(9999L)).isEqualTo(0)
        }

        @DisplayName("이미 삭제된 템플릿을 다시 삭제하면, 0을 반환한다(멱등 아님은 상위 계층에서 NOT_FOUND로 처리).")
        @Test
        fun returnsZero_whenAlreadyDeleted() {
            val saved = couponTemplateRepositoryPort.save(template(name = "삭제 대상"))
            couponTemplateRepositoryPort.delete(saved.id)

            assertThat(couponTemplateRepositoryPort.delete(saved.id)).isEqualTo(0)
        }
    }

    @DisplayName("findAll을 호출할 때, ")
    @Nested
    inner class FindAll {
        @DisplayName("등록된 템플릿이 없으면, 빈 목록과 totalElements=0을 반환한다.")
        @Test
        fun returnsEmpty_whenNoTemplates() {
            val result = couponTemplateRepositoryPort.findAll(PageRequest(page = 0, size = 20))

            assertThat(result.items).isEmpty()
            assertThat(result.totalElements).isEqualTo(0L)
            assertThat(result.totalPages).isEqualTo(0)
        }

        @DisplayName("여러 건 등록 후 조회하면, 페이지 크기·총개수가 맞고 id 내림차순으로 정렬된다.")
        @Test
        fun returnsPagedAndSortedByIdDesc() {
            repeat(25) { index ->
                couponTemplateRepositoryPort.save(template(name = "쿠폰 $index"))
            }

            val firstPage = couponTemplateRepositoryPort.findAll(PageRequest(page = 0, size = 20))

            assertThat(firstPage.items).hasSize(20)
            assertThat(firstPage.totalElements).isEqualTo(25L)
            assertThat(firstPage.totalPages).isEqualTo(2)
            val ids = firstPage.items.map { it.id }
            assertThat(ids).isSortedAccordingTo(Comparator.reverseOrder())

            val secondPage = couponTemplateRepositoryPort.findAll(PageRequest(page = 1, size = 20))

            assertThat(secondPage.items).hasSize(5)
            assertThat(secondPage.totalElements).isEqualTo(25L)
        }
    }
}
