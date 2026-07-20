package com.loopers.application.brand

import com.loopers.application.product.CreateProductCommand
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepositoryPort
import com.loopers.domain.common.PageRequest
import com.loopers.domain.like.LikeService
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderItem
import com.loopers.domain.order.OrderItems
import com.loopers.domain.order.OrderRepositoryPort
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.LikeCountQueryPort
import com.loopers.domain.product.ProductRepositoryPort
import com.loopers.domain.stock.StockRepositoryPort
import com.loopers.interfaces.api.brand.BrandAdminApplicationServicePort
import com.loopers.interfaces.api.product.ProductAdminApplicationServicePort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

// 클래스 @Transactional(롤백) 금지: Brand 캐시가 afterCommit 에 무효화되는데, 롤백 트랜잭션에서는
// evict 가 실행되지 않아 Redis 에 stale 캐시가 남는다(truncate 후 id 재사용과 결합해 다른 테스트까지 오염).
@SpringBootTest
class BrandApplicationServiceIntegrationTest @Autowired constructor(
    private val brandApplicationService: BrandAdminApplicationServicePort,
    private val brandRepositoryPort: BrandRepositoryPort,
    private val productApplicationService: ProductAdminApplicationServicePort,
    private val productRepositoryPort: ProductRepositoryPort,
    private val stockRepositoryPort: StockRepositoryPort,
    private val likeService: LikeService,
    private val likeCountQueryPort: LikeCountQueryPort,
    private val orderRepositoryPort: OrderRepositoryPort,
    private val transactionManager: PlatformTransactionManager,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    // likeService.register 는 비관적 락 쿼리를 쓰므로 활성 트랜잭션이 필요하다.
    // 프로덕션의 LikeApplicationServiceAdapter.like(@Transactional) 경계를 시뮬레이션한다.
    private val txTemplate = TransactionTemplate(transactionManager)

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        // truncate 로 auto_increment 가 리셋되어 id 가 재사용되므로, Redis 캐시도 함께 비워 오염을 막는다
        redisCleanUp.truncateAll()
    }

    @DisplayName("createBrand 통합 흐름")
    @Nested
    inner class CreateBrand {
        @DisplayName("새 브랜드를 등록하면, DB에 저장되고 id가 부여된다.")
        @Test
        fun savesBrand_whenValid() {
            val brand = brandApplicationService.createBrand(CreateBrandCommand(name = "Nike", description = "Just do it"))

            assertThat(brand.id).isGreaterThan(0L)
            assertThat(brandRepositoryPort.findById(brand.id)?.name).isEqualTo("Nike")
        }

        @DisplayName("중복된 name으로 등록하면, CONFLICT 예외가 발생한다.")
        @Test
        fun throwsConflict_whenDuplicateName() {
            brandApplicationService.createBrand(CreateBrandCommand(name = "Nike", description = "x"))

            val result = assertThrows<CoreException> {
                brandApplicationService.createBrand(CreateBrandCommand(name = "Nike", description = "y"))
            }

            assertThat(result.errorType).isEqualTo(ErrorType.CONFLICT)
        }
    }

    @DisplayName("updateBrand 통합 흐름")
    @Nested
    inner class UpdateBrand {
        @DisplayName("브랜드를 수정하면, name/description이 갱신된다.")
        @Test
        fun updatesBrand_whenValid() {
            val saved = brandRepositoryPort.save(Brand.create(name = "Nike", description = "old"))

            val updated = brandApplicationService.updateBrand(UpdateBrandCommand(id = saved.id, name = "Nike2", description = "new"))

            assertThat(updated.name).isEqualTo("Nike2")
            assertThat(updated.description).isEqualTo("new")
            assertThat(brandRepositoryPort.findById(saved.id)?.name).isEqualTo("Nike2")
        }

        @DisplayName("자기 자신의 name으로 수정하면, 충돌 없이 성공한다.")
        @Test
        fun updatesBrand_whenSameName() {
            val saved = brandRepositoryPort.save(Brand.create(name = "Nike", description = "old"))

            val updated = brandApplicationService.updateBrand(UpdateBrandCommand(id = saved.id, name = "Nike", description = "new"))

            assertThat(updated.name).isEqualTo("Nike")
            assertThat(updated.description).isEqualTo("new")
        }

        @DisplayName("다른 브랜드의 name과 충돌하면, CONFLICT 예외가 발생한다.")
        @Test
        fun throwsConflict_whenDuplicate() {
            brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val target = brandRepositoryPort.save(Brand.create(name = "Adidas", description = "y"))

            val result = assertThrows<CoreException> {
                brandApplicationService.updateBrand(UpdateBrandCommand(id = target.id, name = "Nike", description = "z"))
            }

            assertThat(result.errorType).isEqualTo(ErrorType.CONFLICT)
        }

        @DisplayName("존재하지 않는 id로 수정하면, NOT_FOUND 예외가 발생한다.")
        @Test
        fun throwsNotFound_whenMissing() {
            val result = assertThrows<CoreException> {
                brandApplicationService.updateBrand(UpdateBrandCommand(id = 9999L, name = "x", description = "y"))
            }
            assertThat(result.errorType).isEqualTo(ErrorType.NOT_FOUND)
        }
    }

    @DisplayName("deleteBrand 통합 흐름")
    @Nested
    inner class DeleteBrand {
        @DisplayName("브랜드를 삭제하면, soft delete되어 조회되지 않는다.")
        @Test
        fun softDeletesBrand() {
            val saved = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))

            brandApplicationService.deleteBrand(saved.id)

            assertThat(brandRepositoryPort.findById(saved.id)).isNull()
        }

        @DisplayName("존재하지 않는 id로 삭제하면, NOT_FOUND 예외가 발생한다.")
        @Test
        fun throwsNotFound_whenMissing() {
            val result = assertThrows<CoreException> { brandApplicationService.deleteBrand(9999L) }
            assertThat(result.errorType).isEqualTo(ErrorType.NOT_FOUND)
        }

        @DisplayName("브랜드를 삭제하면, 그 브랜드의 모든 상품과 재고가 cascade soft delete된다.")
        @Test
        fun cascadeDeletesProductsAndStocks() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val productDetails = (0 until 3).map {
                productApplicationService.createProduct(
                    CreateProductCommand(name = "p$it", price = 100L, description = "d", brandId = brand.id, quantity = 5),
                )
            }

            brandApplicationService.deleteBrand(brand.id)

            assertThat(brandRepositoryPort.findById(brand.id)).isNull()
            productDetails.forEach {
                assertThat(productRepositoryPort.findById(it.id)).isNull()
                assertThat(stockRepositoryPort.findByProductId(it.id)).isNull()
            }
        }

        @DisplayName("브랜드를 삭제하면, 그 브랜드 상품들의 Like와 LikeCount가 cascade hard delete된다.")
        @Test
        fun cascadeDeletesLikesAndLikeCounts() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val productDetails = (0 until 2).map {
                productApplicationService.createProduct(
                    CreateProductCommand(name = "p$it", price = 100L, description = "d", brandId = brand.id, quantity = 5),
                )
            }
            productDetails.forEach { detail ->
                txTemplate.execute { likeService.register(userId = 1L, productId = detail.id) }
                txTemplate.execute { likeService.register(userId = 2L, productId = detail.id) }
            }
            productDetails.forEach { detail ->
                assertThat(likeCountQueryPort.countByProductId(detail.id)).isEqualTo(2L)
            }

            brandApplicationService.deleteBrand(brand.id)

            productDetails.forEach { detail ->
                assertThat(likeCountQueryPort.countByProductId(detail.id)).isEqualTo(0L)
                assertThat(likeService.findAllProductIdsByUserId(1L)).doesNotContain(detail.id)
                assertThat(likeService.findAllProductIdsByUserId(2L)).doesNotContain(detail.id)
            }
        }

        @DisplayName("브랜드를 삭제해도 다른 브랜드의 상품과 재고는 영향받지 않는다.")
        @Test
        fun cascadeDoesNotAffectOtherBrands() {
            val brand1 = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val brand2 = brandRepositoryPort.save(Brand.create(name = "Adidas", description = "y"))
            val survivor = productApplicationService.createProduct(
                CreateProductCommand(name = "survivor", price = 100L, description = "d", brandId = brand2.id, quantity = 5),
            )
            productApplicationService.createProduct(
                CreateProductCommand(name = "victim", price = 100L, description = "d", brandId = brand1.id, quantity = 5),
            )

            brandApplicationService.deleteBrand(brand1.id)

            assertThat(productRepositoryPort.findById(survivor.id)).isNotNull
            assertThat(stockRepositoryPort.findByProductId(survivor.id)).isNotNull
        }

        @DisplayName("브랜드 상품 중 미완료 주문이 있는 상품이 있으면 CONFLICT 예외가 발생한다.")
        @Test
        fun throwsConflict_whenAnyBrandProductHasIncompleteOrder() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "p", price = 100L, description = "d", brandId = brand.id, quantity = 5),
            )
            saveIncompleteOrderFor(detail.id)

            val result = assertThrows<CoreException> { brandApplicationService.deleteBrand(brand.id) }

            assertThat(result.errorType).isEqualTo(ErrorType.CONFLICT)
            assertThat(brandRepositoryPort.findById(brand.id)).isNotNull
        }
    }

    private fun saveIncompleteOrderFor(productId: Long, userId: Long = 1L) {
        val base = Order.create(
            userId = userId,
            items = OrderItems(
                listOf(
                    OrderItem(
                        productId = productId,
                        quantity = 1,
                        snapshotProductName = "snapshot",
                        snapshotPrice = 1_000L,
                        snapshotBrandName = "SnapBrand",
                    ),
                ),
            ),
            orderedAt = ZonedDateTime.now(),
        )
        val saved = orderRepositoryPort.save(base)
        orderRepositoryPort.save(saved.updateStatus(OrderStatus.PAYMENT_PENDING))
    }

    @DisplayName("getBrands 통합 흐름")
    @Nested
    inner class GetBrands {
        @DisplayName("페이지 단위로 브랜드 목록을 반환한다.")
        @Test
        fun returnsPagedBrands() {
            repeat(3) { brandApplicationService.createBrand(CreateBrandCommand(name = "brand-$it", description = "d-$it")) }

            val result = brandApplicationService.getBrands(PageRequest(page = 0, size = 10))

            assertThat(result.items).hasSize(3)
            assertThat(result.totalElements).isEqualTo(3L)
        }
    }
}
