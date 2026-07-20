package com.loopers.interfaces.api

import com.loopers.application.product.CreateProductCommand
import com.loopers.application.product.UpdateProductCommand
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepositoryPort
import com.loopers.interfaces.api.like.LikeApplicationServicePort
import com.loopers.interfaces.api.product.ProductAdminApplicationServicePort
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val brandRepositoryPort: BrandRepositoryPort,
    private val productApplicationService: ProductAdminApplicationServicePort,
    private val likeApplicationService: LikeApplicationServicePort,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisCleanUp: RedisCleanUp,
) {
    companion object {
        private const val PRODUCT_ENDPOINT = "/api/v1/products"
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    private fun getProduct(id: Long): org.springframework.http.ResponseEntity<ApiResponse<Any>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val responseType = object : ParameterizedTypeReference<ApiResponse<Any>>() {}
        return testRestTemplate.exchange(
            "$PRODUCT_ENDPOINT/$id",
            HttpMethod.GET,
            HttpEntity<Any>(headers),
            responseType,
        )
    }

    private fun listProducts(
        brandId: Long? = null,
        sort: String? = null,
        page: Int? = null,
        size: Int? = null,
    ): org.springframework.http.ResponseEntity<ApiResponse<Any>> {
        val params = buildList {
            brandId?.let { add("brandId=$it") }
            sort?.let { add("sort=$it") }
            page?.let { add("page=$it") }
            size?.let { add("size=$it") }
        }
        val url = if (params.isEmpty()) PRODUCT_ENDPOINT else "$PRODUCT_ENDPOINT?${params.joinToString("&")}"
        return testRestTemplate.exchange(
            url,
            HttpMethod.GET,
            HttpEntity<Any>(HttpHeaders()),
            object : ParameterizedTypeReference<ApiResponse<Any>>() {},
        )
    }

    @DisplayName("GET /api/v1/products/{id}")
    @Nested
    inner class GetProductDetail {

        @DisplayName("존재하는 상품 id로 조회하면, brandName/stockQuantity/likeCount를 포함해 반환한다.")
        @Test
        fun returnsProductDetail_whenExists() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "Just do it"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "에어맥스", price = 100000L, description = "d", brandId = brand.id, quantity = 30),
            )

            val response = getProduct(detail.id)

            val data = response.body?.data as? Map<*, *>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(data?.get("id")).isEqualTo(detail.id.toInt()) },
                { assertThat(data?.get("name")).isEqualTo("에어맥스") },
                { assertThat((data?.get("price") as? Number)?.toLong()).isEqualTo(100000L) },
                { assertThat(data?.get("brandId")).isEqualTo(brand.id.toInt()) },
                { assertThat(data?.get("brandName")).isEqualTo("Nike") },
                { assertThat(data?.get("stockQuantity")).isEqualTo(30) },
                { assertThat((data?.get("likeCount") as? Number)?.toLong()).isEqualTo(0L) },
            )
        }

        @DisplayName("존재하지 않는 상품 id로 조회하면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        fun returnsNotFound_whenMissing() {
            val response = getProduct(9999L)
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }

        @DisplayName("삭제된 상품 id로 조회하면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        fun returnsNotFound_whenDeleted() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "p", price = 100L, description = "d", brandId = brand.id, quantity = 5),
            )
            productApplicationService.deleteProduct(detail.id)

            val response = getProduct(detail.id)
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @DisplayName("GET /api/v1/products (SC-PRODUCT-1)")
    @Nested
    inner class ListProducts {

        @DisplayName("정렬 옵션 없이 조회하면 LATEST 기본 정렬로 전체 상품을 반환한다.")
        @Test
        fun returnsAllProducts_byDefault() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            productApplicationService.createProduct(CreateProductCommand(name = "a", price = 100L, description = "d", brandId = brand.id, quantity = 1))
            productApplicationService.createProduct(CreateProductCommand(name = "b", price = 200L, description = "d", brandId = brand.id, quantity = 1))

            val response = listProducts()

            val data = response.body?.data as? Map<*, *>
            val items = data?.get("items") as? List<*>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(items?.size).isEqualTo(2) },
            )
        }

        @DisplayName("price_asc 정렬을 적용하면 가격 오름차순으로 반환한다.")
        @Test
        fun sortsByPriceAsc() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            productApplicationService.createProduct(CreateProductCommand(name = "high", price = 500L, description = "d", brandId = brand.id, quantity = 1))
            productApplicationService.createProduct(CreateProductCommand(name = "low", price = 100L, description = "d", brandId = brand.id, quantity = 1))
            productApplicationService.createProduct(CreateProductCommand(name = "mid", price = 300L, description = "d", brandId = brand.id, quantity = 1))

            val response = listProducts(sort = "price_asc")

            val data = response.body?.data as? Map<*, *>
            val items = data?.get("items") as? List<*>
            val prices = items?.map { ((it as Map<*, *>)["price"] as Number).toLong() }
            assertThat(prices).containsExactly(100L, 300L, 500L)
        }

        @DisplayName("likes_desc 정렬을 적용하면 좋아요 수 내림차순으로 반환한다.")
        @Test
        fun sortsByLikesDesc() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val popular = productApplicationService.createProduct(CreateProductCommand(name = "popular", price = 100L, description = "d", brandId = brand.id, quantity = 1)).id
            val quiet = productApplicationService.createProduct(CreateProductCommand(name = "quiet", price = 100L, description = "d", brandId = brand.id, quantity = 1)).id
            // likeService.register 직접 호출은 비관적 락 쿼리 때문에 활성 트랜잭션이 필요하다.
            // E2E 답게 트랜잭션 경계를 가진 애플리케이션 서비스 경유로 등록한다.
            likeApplicationService.like(userId = 1L, productId = popular)
            likeApplicationService.like(userId = 2L, productId = popular)

            val response = listProducts(sort = "likes_desc")

            val data = response.body?.data as? Map<*, *>
            val items = data?.get("items") as? List<*>
            val ids = items?.map { ((it as Map<*, *>)["id"] as Number).toLong() }
            assertThat(ids?.first()).isEqualTo(popular)
            assertThat(ids).contains(quiet)
        }

        @DisplayName("brandId 필터를 함께 적용하면 해당 브랜드 상품만 반환한다.")
        @Test
        fun filtersByBrandId() {
            val brand1 = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val brand2 = brandRepositoryPort.save(Brand.create(name = "Adidas", description = "y"))
            productApplicationService.createProduct(CreateProductCommand(name = "n", price = 100L, description = "d", brandId = brand1.id, quantity = 1))
            productApplicationService.createProduct(CreateProductCommand(name = "a", price = 100L, description = "d", brandId = brand2.id, quantity = 1))

            val response = listProducts(brandId = brand1.id)

            val data = response.body?.data as? Map<*, *>
            val items = data?.get("items") as? List<*>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(items?.size).isEqualTo(1) },
                { assertThat(((items?.first() as Map<*, *>)["brandName"])).isEqualTo("Nike") },
            )
        }

        @DisplayName("상품이 없으면 빈 items와 totalElements 0을 200으로 반환한다.")
        @Test
        fun returnsEmpty_whenNoProducts() {
            val response = listProducts()
            val data = response.body?.data as? Map<*, *>
            val items = data?.get("items") as? List<*>
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(items?.size).isEqualTo(0) },
                { assertThat((data?.get("totalElements") as? Number)?.toLong()).isEqualTo(0L) },
            )
        }

        @DisplayName("지원하지 않는 sort 값으로 조회하면 400 BAD_REQUEST 응답을 받는다.")
        @Test
        fun returnsBadRequest_whenSortInvalid() {
            val response = listProducts(sort = "unknown")
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }

        @DisplayName("페이지네이션 파라미터를 적용해 결과를 분할 반환한다.")
        @Test
        fun paginates() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            repeat(5) { idx ->
                productApplicationService.createProduct(CreateProductCommand(name = "p$idx", price = 100L, description = "d", brandId = brand.id, quantity = 1))
            }

            val response = listProducts(sort = "price_asc", page = 0, size = 2)

            val data = response.body?.data as? Map<*, *>
            val items = data?.get("items") as? List<*>
            assertAll(
                { assertThat(items?.size).isEqualTo(2) },
                { assertThat((data?.get("totalElements") as? Number)?.toLong()).isEqualTo(5L) },
                { assertThat((data?.get("totalPages") as? Number)?.toInt()).isEqualTo(3) },
            )
        }
    }

    @DisplayName("상품 상세 캐싱 (Redis)")
    @Nested
    inner class DetailCaching {

        @DisplayName("동일 상품을 두 번 조회해도 같은 응답을 반환한다(캐시 경유).")
        @Test
        fun returnsSameDetail_onRepeatedReads() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "에어맥스", price = 100000L, description = "d", brandId = brand.id, quantity = 30),
            )

            val first = getProduct(detail.id).body?.data as? Map<*, *>
            val second = getProduct(detail.id).body?.data as? Map<*, *>

            assertThat(second).isEqualTo(first)
            assertThat(second?.get("name")).isEqualTo("에어맥스")
        }

        @DisplayName("상품을 수정하면 캐시가 무효화되어 다음 조회에 최신 값이 반영된다.")
        @Test
        fun reflectsUpdate_afterEviction() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "old", price = 100L, description = "d", brandId = brand.id, quantity = 30),
            )
            getProduct(detail.id) // 캐시 적재

            productApplicationService.updateProduct(
                UpdateProductCommand(id = detail.id, name = "new", price = 999L, description = "d2", brandId = brand.id, quantity = 30),
            )

            val data = getProduct(detail.id).body?.data as? Map<*, *>
            assertAll(
                { assertThat(data?.get("name")).isEqualTo("new") },
                { assertThat((data?.get("price") as? Number)?.toLong()).isEqualTo(999L) },
            )
        }

        @DisplayName("정적 필드는 캐시되어도 likeCount는 실시간으로 반영된다(변동 필드 분리).")
        @Test
        fun likeCountIsRealtime_evenWhenStaticCached() {
            val brand = brandRepositoryPort.save(Brand.create(name = "Nike", description = "x"))
            val detail = productApplicationService.createProduct(
                CreateProductCommand(name = "p", price = 100L, description = "d", brandId = brand.id, quantity = 30),
            )

            val before = getProduct(detail.id).body?.data as? Map<*, *>
            assertThat((before?.get("likeCount") as? Number)?.toLong()).isEqualTo(0L)

            likeApplicationService.like(userId = 1L, productId = detail.id) // 상품 정적 캐시는 건드리지 않음

            val after = getProduct(detail.id).body?.data as? Map<*, *>
            assertThat((after?.get("likeCount") as? Number)?.toLong()).isEqualTo(1L)
        }
    }
}
