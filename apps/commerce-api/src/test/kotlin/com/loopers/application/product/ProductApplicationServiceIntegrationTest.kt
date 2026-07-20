package com.loopers.application.product

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
import com.loopers.domain.product.ProductSort
import com.loopers.domain.stock.StockRepositoryPort
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

// 클래스 @Transactional(롤백) 금지: Brand/Product 캐시가 afterCommit 에 무효화되는데, 롤백 트랜잭션에서는
// evict 가 실행되지 않아 Redis 에 stale 캐시가 남는다(truncate 후 id 재사용과 결합해 다른 테스트까지 오염).
@SpringBootTest
class ProductApplicationServiceIntegrationTest @Autowired constructor(
    private val productApplicationService: ProductAdminApplicationServicePort,
    private val productRepositoryPort: ProductRepositoryPort,
    private val stockRepositoryPort: StockRepositoryPort,
    private val brandRepositoryPort: BrandRepositoryPort,
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

    @DisplayName("createProduct 통합 흐름")
    @Nested
    inner class Create {
        @DisplayName("Brand가 존재하면 Product와 Stock이 함께 저장된다.")
        @Test
        fun createsProductAndStock() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "에어맥스", price = 100000L, description = "d", brandId = brand.id, quantity = 50),
            )

            assertThat(detail.id).isGreaterThan(0L)
            assertThat(detail.stockQuantity).isEqualTo(50)
            assertThat(stockRepositoryPort.findByProductId(detail.id)?.quantity).isEqualTo(50)
        }

        @DisplayName("createProduct 시 LikeCount가 0으로 초기화된다.")
        @Test
        fun initializesLikeCountToZero() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "p", price = 100L, description = "d", brandId = brand.id, quantity = 10),
            )

            assertThat(likeCountQueryPort.countByProductId(detail.id)).isEqualTo(0L)
        }

        @DisplayName("Brand가 없으면 NOT_FOUND 예외가 발생한다.")
        @Test
        fun throwsNotFound_whenBrandMissing() {
            val result = assertThrows<CoreException> {
                productApplicationService.createProduct(
                    CreateProductCommand(name = "x", price = 100L, description = "d", brandId = 9999L, quantity = 10),
                )
            }
            assertThat(result.errorType).isEqualTo(ErrorType.NOT_FOUND)
        }
    }

    @DisplayName("updateProduct 통합 흐름")
    @Nested
    inner class Update {
        @DisplayName("Product와 Stock이 함께 갱신된다.")
        @Test
        fun updatesProductAndStock() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "old", price = 100L, description = "d", brandId = brand.id, quantity = 10),
            )

            val updated = productApplicationService.updateProduct(
                UpdateProductCommand(id = detail.id, name = "new", price = 200L, description = "newD", brandId = brand.id, quantity = 99),
            )

            assertThat(updated.name).isEqualTo("new")
            assertThat(updated.price).isEqualTo(200L)
            assertThat(updated.stockQuantity).isEqualTo(99)
            assertThat(stockRepositoryPort.findByProductId(detail.id)?.quantity).isEqualTo(99)
        }

        @DisplayName("brandId를 변경하려고 하면 BAD_REQUEST 예외가 발생한다.")
        @Test
        fun throwsBadRequest_whenBrandIdChanged() {
            val brand1 = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val brand2 = brandRepositoryPort.save(Brand.create(name = "Adidas", description = "y"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "p", price = 100L, description = "d", brandId = brand1.id, quantity = 10),
            )

            val result = assertThrows<CoreException> {
                productApplicationService.updateProduct(
                    UpdateProductCommand(id = detail.id, name = "p", price = 100L, description = "d", brandId = brand2.id, quantity = 10),
                )
            }
            assertThat(result.errorType).isEqualTo(ErrorType.BAD_REQUEST)
        }
    }

    @DisplayName("deleteProduct 통합 흐름")
    @Nested
    inner class Delete {
        @DisplayName("Product와 Stock이 함께 soft delete된다.")
        @Test
        fun softDeletesProductAndStock() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "p", price = 100L, description = "d", brandId = brand.id, quantity = 10),
            )

            productApplicationService.deleteProduct(detail.id)

            assertThat(productRepositoryPort.findById(detail.id)).isNull()
            assertThat(stockRepositoryPort.findByProductId(detail.id)).isNull()
        }

        @DisplayName("deleteProduct 시 Like와 LikeCount도 cascade hard delete된다.")
        @Test
        fun cascadesLikeAndLikeCount() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "p", price = 100L, description = "d", brandId = brand.id, quantity = 10),
            )
            txTemplate.execute { likeService.register(userId = 1L, productId = detail.id) }
            txTemplate.execute { likeService.register(userId = 2L, productId = detail.id) }
            assertThat(likeCountQueryPort.countByProductId(detail.id)).isEqualTo(2L)

            productApplicationService.deleteProduct(detail.id)

            assertThat(likeCountQueryPort.countByProductId(detail.id)).isEqualTo(0L)
            assertThat(likeService.findAllProductIdsByUserId(1L)).doesNotContain(detail.id)
            assertThat(likeService.findAllProductIdsByUserId(2L)).doesNotContain(detail.id)
        }

        @DisplayName("미완료 주문이 있는 상품을 삭제하면 CONFLICT 예외가 발생한다.")
        @Test
        fun throwsConflict_whenIncompleteOrderExists() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "p", price = 100L, description = "d", brandId = brand.id, quantity = 10),
            )
            saveIncompleteOrderFor(productId = detail.id)

            val result = assertThrows<CoreException> { productApplicationService.deleteProduct(detail.id) }

            assertThat(result.errorType).isEqualTo(ErrorType.CONFLICT)
            assertThat(productRepositoryPort.findById(detail.id)).isNotNull
        }

        @DisplayName("완료된 주문(CANCELLED/DELIVERED)만 있으면 상품을 삭제할 수 있다.")
        @Test
        fun deletes_whenAllOrdersCompleted() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "p", price = 100L, description = "d", brandId = brand.id, quantity = 10),
            )
            saveCancelledOrderFor(productId = detail.id)

            productApplicationService.deleteProduct(detail.id)

            assertThat(productRepositoryPort.findById(detail.id)).isNull()
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

    private fun saveCancelledOrderFor(productId: Long, userId: Long = 1L) {
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
        val pending = orderRepositoryPort.save(saved.updateStatus(OrderStatus.PAYMENT_PENDING))
        orderRepositoryPort.save(pending.cancel())
    }

    @DisplayName("getProduct / getProducts 통합 흐름")
    @Nested
    inner class Query {
        @DisplayName("getProduct는 brandName, stockQuantity, likeCount(초기값 0)를 포함한 ProductDetail을 반환한다.")
        @Test
        fun returnsDetail() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val created = productApplicationService.createProduct(
                CreateProductCommand(name = "에어맥스", price = 100L, description = "d", brandId = brand.id, quantity = 20),
            )

            val detail = productApplicationService.getProduct(created.id)

            assertThat(detail.brandName).isEqualTo("Nike")
            assertThat(detail.stockQuantity).isEqualTo(20)
            assertThat(detail.likeCount).isEqualTo(0L)
        }

        @DisplayName("getProducts(brandId=null)은 전체 상품을 반환한다.")
        @Test
        fun returnsAllProducts() {
            val brand1 = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val brand2 = brandRepositoryPort.save(Brand.create(name = "Adidas", description = "y"))
            repeat(2) {
                productApplicationService.createProduct(CreateProductCommand(name = "n$it", price = 100L, description = "d", brandId = brand1.id, quantity = 1))
            }
            productApplicationService.createProduct(CreateProductCommand(name = "a", price = 100L, description = "d", brandId = brand2.id, quantity = 1))

            val result = productApplicationService.getProducts(null, ProductSort.LATEST, PageRequest(page = 0, size = 10))

            assertThat(result.items).hasSize(3)
        }

        @DisplayName("getProducts(brandId=특정)은 해당 브랜드 상품만 반환한다.")
        @Test
        fun returnsFilteredByBrand() {
            val brand1 = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val brand2 = brandRepositoryPort.save(Brand.create(name = "Adidas", description = "y"))
            repeat(2) {
                productApplicationService.createProduct(CreateProductCommand(name = "n$it", price = 100L, description = "d", brandId = brand1.id, quantity = 1))
            }
            productApplicationService.createProduct(CreateProductCommand(name = "a", price = 100L, description = "d", brandId = brand2.id, quantity = 1))

            val result = productApplicationService.getProducts(brand1.id, ProductSort.LATEST, PageRequest(page = 0, size = 10))

            assertThat(result.items).hasSize(2)
            assertThat(result.items.all { it.brandId == brand1.id }).isTrue()
        }

        @DisplayName("getProducts는 각 상품의 좋아요 수(likeCount)를 포함해 반환한다.")
        @Test
        fun includesLikeCountPerSummary() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val popularProductId = productApplicationService.createProduct(
                CreateProductCommand(name = "popular", price = 100L, description = "d", brandId = brand.id, quantity = 1),
            ).id
            val quietProductId = productApplicationService.createProduct(
                CreateProductCommand(name = "quiet", price = 100L, description = "d", brandId = brand.id, quantity = 1),
            ).id
            txTemplate.execute { likeService.register(userId = 1L, productId = popularProductId) }
            txTemplate.execute { likeService.register(userId = 2L, productId = popularProductId) }
            txTemplate.execute { likeService.register(userId = 3L, productId = popularProductId) }

            val result = productApplicationService.getProducts(null, ProductSort.LATEST, PageRequest(page = 0, size = 10))

            val byId = result.items.associate { it.id to it.likeCount }
            assertThat(byId[popularProductId]).isEqualTo(3L)
            assertThat(byId[quietProductId]).isEqualTo(0L)
        }
    }

    @DisplayName("getProducts 정렬 옵션")
    @Nested
    inner class Sorting {
        @DisplayName("PRICE_ASC는 가격 오름차순으로 정렬한다.")
        @Test
        fun sortsByPriceAsc() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            productApplicationService.createProduct(CreateProductCommand(name = "expensive", price = 500L, description = "d", brandId = brand.id, quantity = 1))
            productApplicationService.createProduct(CreateProductCommand(name = "cheap", price = 100L, description = "d", brandId = brand.id, quantity = 1))
            productApplicationService.createProduct(CreateProductCommand(name = "mid", price = 300L, description = "d", brandId = brand.id, quantity = 1))

            val result = productApplicationService.getProducts(null, ProductSort.PRICE_ASC, PageRequest(page = 0, size = 10))

            val prices = result.items.map { it.price }
            assertThat(prices).containsExactly(100L, 300L, 500L)
        }

        @DisplayName("LATEST는 최신 생성순(createdAt DESC)으로 정렬한다.")
        @Test
        fun sortsByLatest() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val first = productApplicationService.createProduct(CreateProductCommand(name = "first", price = 100L, description = "d", brandId = brand.id, quantity = 1))
            Thread.sleep(10)
            val second = productApplicationService.createProduct(CreateProductCommand(name = "second", price = 100L, description = "d", brandId = brand.id, quantity = 1))
            Thread.sleep(10)
            val third = productApplicationService.createProduct(CreateProductCommand(name = "third", price = 100L, description = "d", brandId = brand.id, quantity = 1))

            val result = productApplicationService.getProducts(null, ProductSort.LATEST, PageRequest(page = 0, size = 10))

            val ids = result.items.map { it.id }
            assertThat(ids.first()).isEqualTo(third.id)
            assertThat(ids.last()).isEqualTo(first.id)
            assertThat(ids).contains(second.id)
        }

        @DisplayName("LIKES_DESC는 좋아요 수 내림차순으로 정렬한다.")
        @Test
        fun sortsByLikesDesc() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val popular = productApplicationService.createProduct(CreateProductCommand(name = "popular", price = 100L, description = "d", brandId = brand.id, quantity = 1)).id
            val quiet = productApplicationService.createProduct(CreateProductCommand(name = "quiet", price = 100L, description = "d", brandId = brand.id, quantity = 1)).id
            val mid = productApplicationService.createProduct(CreateProductCommand(name = "mid", price = 100L, description = "d", brandId = brand.id, quantity = 1)).id
            txTemplate.execute { likeService.register(userId = 1L, productId = popular) }
            txTemplate.execute { likeService.register(userId = 2L, productId = popular) }
            txTemplate.execute { likeService.register(userId = 3L, productId = popular) }
            txTemplate.execute { likeService.register(userId = 1L, productId = mid) }

            val result = productApplicationService.getProducts(null, ProductSort.LIKES_DESC, PageRequest(page = 0, size = 10))

            val ids = result.items.map { it.id }
            assertThat(ids).containsExactly(popular, mid, quiet)
        }

        @DisplayName("좋아요 등록 직후 LIKES_DESC 정렬에서 순위가 갱신된다.")
        @Test
        fun reflectsLikeRegistration() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val a = productApplicationService.createProduct(CreateProductCommand(name = "a", price = 100L, description = "d", brandId = brand.id, quantity = 1)).id
            val b = productApplicationService.createProduct(CreateProductCommand(name = "b", price = 100L, description = "d", brandId = brand.id, quantity = 1)).id

            // 처음에는 like 없음 → id 보조 정렬에 의해 b가 앞 (id 큰 쪽)
            val before = productApplicationService.getProducts(null, ProductSort.LIKES_DESC, PageRequest(page = 0, size = 10))
            assertThat(before.items.first().id).isEqualTo(b)

            // a에 좋아요 2개 → a가 앞으로
            txTemplate.execute { likeService.register(userId = 1L, productId = a) }
            txTemplate.execute { likeService.register(userId = 2L, productId = a) }
            val after = productApplicationService.getProducts(null, ProductSort.LIKES_DESC, PageRequest(page = 0, size = 10))
            assertThat(after.items.first().id).isEqualTo(a)
        }

        @DisplayName("brandId 필터와 정렬을 함께 적용한다.")
        @Test
        fun appliesBrandFilterAndSort() {
            val brand1 = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val brand2 = brandRepositoryPort.save(Brand.create(name = "Adidas", description = "y"))
            productApplicationService.createProduct(CreateProductCommand(name = "n-high", price = 500L, description = "d", brandId = brand1.id, quantity = 1))
            productApplicationService.createProduct(CreateProductCommand(name = "n-low", price = 100L, description = "d", brandId = brand1.id, quantity = 1))
            productApplicationService.createProduct(CreateProductCommand(name = "a", price = 50L, description = "d", brandId = brand2.id, quantity = 1))

            val result = productApplicationService.getProducts(brand1.id, ProductSort.PRICE_ASC, PageRequest(page = 0, size = 10))

            assertThat(result.items).hasSize(2)
            assertThat(result.items.map { it.price }).containsExactly(100L, 500L)
            assertThat(result.items.all { it.brandId == brand1.id }).isTrue()
        }

        @DisplayName("빈 결과는 0개 items와 totalElements 0을 반환한다.")
        @Test
        fun returnsEmpty_whenNoProducts() {
            val result = productApplicationService.getProducts(null, ProductSort.LATEST, PageRequest(page = 0, size = 10))
            assertThat(result.items).isEmpty()
            assertThat(result.totalElements).isEqualTo(0L)
        }
    }
}
