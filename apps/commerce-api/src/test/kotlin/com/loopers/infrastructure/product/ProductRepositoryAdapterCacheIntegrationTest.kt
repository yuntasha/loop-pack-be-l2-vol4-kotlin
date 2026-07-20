package com.loopers.infrastructure.product

import com.loopers.config.redis.RedisConfig
import com.loopers.domain.common.PageRequest
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepositoryPort
import com.loopers.domain.product.ProductSort
import com.loopers.infrastructure.cache.CacheKeys
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.RedisTemplate

/**
 * ProductRepositoryAdapter 내부 캐싱의 통합 테스트.
 * 캐싱은 어댑터 내부 구현이므로, DB를 직접 우회 조작해 "캐시가 실제로 사용되는지"를 관찰한다.
 */
@Suppress("UNCHECKED_CAST")
@SpringBootTest
class ProductRepositoryAdapterCacheIntegrationTest @Autowired constructor(
    private val productRepositoryPort: ProductRepositoryPort,
    private val productJpaRepository: ProductJpaRepository,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    masterTemplate: RedisTemplate<*, *>,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    private val redis = masterTemplate as RedisTemplate<String, String>

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    /** 캐시를 우회해 DB의 name만 직접 바꾼다. */
    private fun mutateNameDirectly(id: Long, newName: String) {
        val entity = productJpaRepository.findById(id).orElseThrow()
        entity.update(name = newName, price = entity.price, description = entity.description)
        productJpaRepository.save(entity)
    }

    @DisplayName("findById 단건 캐시: ")
    @Nested
    inner class FindByIdCache {
        @DisplayName("두 번째 조회는 캐시에서 반환된다(그 사이 DB가 바뀌어도 캐시 값 유지).")
        @Test
        fun secondReadHitsCache() {
            val saved = productRepositoryPort.save(Product.create(name = "A", price = 100L, description = "d", brandId = 1L))

            assertThat(productRepositoryPort.findById(saved.id)?.name).isEqualTo("A") // miss → 적재
            mutateNameDirectly(saved.id, "B") // DB만 변경(캐시 무효화 우회)

            assertThat(productRepositoryPort.findById(saved.id)?.name).isEqualTo("A") // 캐시 히트
            assertThat(redis.getExpire(CacheKeys.product(saved.id))).isGreaterThan(0L)
        }

        @DisplayName("save(update) 후에는 캐시가 무효화되어 최신 값을 반환한다.")
        @Test
        fun saveEvictsCache() {
            val saved = productRepositoryPort.save(Product.create(name = "A", price = 100L, description = "d", brandId = 1L))
            productRepositoryPort.findById(saved.id) // 캐시 적재

            productRepositoryPort.save(saved.update(name = "C", price = 100L, description = "d")) // evict

            assertThat(productRepositoryPort.findById(saved.id)?.name).isEqualTo("C")
        }

        @DisplayName("delete 후에는 캐시가 무효화되어 null을 반환한다.")
        @Test
        fun deleteEvictsCache() {
            val saved = productRepositoryPort.save(Product.create(name = "A", price = 100L, description = "d", brandId = 1L))
            productRepositoryPort.findById(saved.id) // 캐시 적재

            productRepositoryPort.delete(saved)

            assertThat(productRepositoryPort.findById(saved.id)).isNull()
            assertThat(redis.opsForValue().get(CacheKeys.product(saved.id))).isNull()
        }
    }

    @DisplayName("findAll 좋아요순 페이지 캐시: ")
    @Nested
    inner class PageCache {
        @DisplayName("brandId + LIKES_DESC 조회는 캐시되며 TTL과 함께 동일 결과를 반환한다.")
        @Test
        fun cachesLikeDescPage() {
            productRepositoryPort.save(Product.create(name = "a", price = 100L, description = "d", brandId = 1L))
            productRepositoryPort.save(Product.create(name = "b", price = 100L, description = "d", brandId = 1L))

            val pageRequest = PageRequest(page = 0, size = 20)
            val first = productRepositoryPort.findAll(1L, ProductSort.LIKES_DESC, pageRequest)
            assertThat(first.items).hasSize(2)

            // DB에 상품을 추가해도 캐시된 페이지는 그대로(2개) 반환된다.
            productRepositoryPort.save(Product.create(name = "c", price = 100L, description = "d", brandId = 1L))
            val second = productRepositoryPort.findAll(1L, ProductSort.LIKES_DESC, pageRequest)

            assertThat(second.items).hasSize(2)
            val key = CacheKeys.likeDescPage(1L, 0, 20)
            // PAGE_TTL(60s)에 ±10% jitter 가 적용되므로 상한은 66s
            assertThat(redis.getExpire(key)).isGreaterThan(0L).isLessThanOrEqualTo(66L)
        }

        @DisplayName("brandId가 없으면 캐시하지 않는다(매번 최신 DB 결과).")
        @Test
        fun doesNotCacheWhenBrandIdNull() {
            productRepositoryPort.save(Product.create(name = "a", price = 100L, description = "d", brandId = 1L))
            val pageRequest = PageRequest(page = 0, size = 20)

            productRepositoryPort.findAll(null, ProductSort.LIKES_DESC, pageRequest)
            productRepositoryPort.save(Product.create(name = "b", price = 100L, description = "d", brandId = 1L))
            val second = productRepositoryPort.findAll(null, ProductSort.LIKES_DESC, pageRequest)

            assertThat(second.items).hasSize(2)
        }
    }
}
