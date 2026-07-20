package com.loopers.application.product

import com.loopers.domain.brand.BrandService
import com.loopers.domain.common.PageRequest
import com.loopers.domain.common.PageResult
import com.loopers.domain.like.LikeService
import com.loopers.domain.order.OrderService
import com.loopers.domain.product.LikeCountQueryPort
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductDetail
import com.loopers.domain.product.ProductService
import com.loopers.domain.product.ProductSort
import com.loopers.domain.product.ProductSummary
import com.loopers.domain.ranking.RankingEntry
import com.loopers.domain.ranking.RankingService
import com.loopers.interfaces.api.product.ProductAdminApplicationServicePort
import com.loopers.interfaces.api.product.ProductApplicationServicePort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

@Service
class ProductApplicationServiceAdapter(
    private val productService: ProductService,
    private val brandService: BrandService,
    private val likeService: LikeService,
    private val likeCountQueryPort: LikeCountQueryPort,
    private val orderService: OrderService,
    private val rankingService: RankingService,
    private val eventPublisher: ApplicationEventPublisher,
) : ProductApplicationServicePort,
    ProductAdminApplicationServicePort {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun getProduct(id: Long): ProductDetail {
        val product = productService.getById(id)
        eventPublisher.publishEvent(ProductViewedEvent(productId = product.id))
        return composeDetail(product)
    }

    @Transactional(readOnly = true)
    override fun getProducts(brandId: Long?, sort: ProductSort, pageRequest: PageRequest): PageResult<ProductSummary> {
        val products = productService.getAll(brandId, sort, pageRequest)
        val summaries = composeSummaries(products.items)
        return PageResult(
            items = summaries,
            page = products.page,
            size = products.size,
            totalElements = products.totalElements,
            totalPages = products.totalPages,
        )
    }

    @Transactional
    override fun createProduct(command: CreateProductCommand): ProductDetail {
        val brand = brandService.getById(command.brandId)
        val (product, stock) = productService.create(
            name = command.name,
            price = command.price,
            description = command.description,
            brandId = command.brandId,
            quantity = command.quantity,
        )
        likeService.initializeForProduct(product.id)
        return ProductDetail.of(product, brand, stock, likeCount = 0L)
    }

    @Transactional
    override fun updateProduct(command: UpdateProductCommand): ProductDetail {
        val (updatedProduct, _) = productService.update(
            id = command.id,
            name = command.name,
            price = command.price,
            description = command.description,
            brandId = command.brandId,
            quantity = command.quantity,
        )
        return composeDetail(updatedProduct)
    }

    @Transactional
    override fun deleteProduct(id: Long) {
        if (orderService.hasIncompleteOrdersForProducts(listOf(id))) {
            throw CoreException(ErrorType.CONFLICT, "미완료 주문이 있는 상품은 삭제할 수 없습니다.")
        }
        productService.delete(id)
        likeService.deleteAllByProductId(id)
    }

    private fun composeDetail(product: Product): ProductDetail {
        val brand = brandService.getById(product.brandId)
        val stock = productService.getStockByProductId(product.id)
        val likeCount = likeCountQueryPort.countByProductId(product.id)
        return ProductDetail.of(product, brand, stock, likeCount, ranking = todayRankingOf(product.id))
    }

    /** 랭킹은 부가 정보라 Redis 장애가 상세 조회를 막지 않도록 실패를 null로 흡수한다. */
    private fun todayRankingOf(productId: Long): RankingEntry? =
        runCatching { rankingService.getProductRanking(LocalDate.now(ZoneId.of("Asia/Seoul")), productId) }
            .onFailure { log.warn("상품 상세 랭킹 병합 실패. productId={}", productId, it) }
            .getOrNull()

    private fun composeSummaries(products: List<Product>): List<ProductSummary> {
        if (products.isEmpty()) return emptyList()
        val productIds = products.map { it.id }
        val brandIds = products.map { it.brandId }.distinct()
        val brandsById = brandService.findAllByIds(brandIds).associateBy { it.id }
        val stocksByProductId = productService.findAllStocksByProductIds(productIds).associateBy { it.productId }
        val likeCounts = likeCountQueryPort.countsByProductIds(productIds)
        return products.map { product ->
            val brand = brandsById[product.brandId]
                ?: throw CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다.")
            val stock = stocksByProductId[product.id]
                ?: throw CoreException(ErrorType.NOT_FOUND, "재고를 찾을 수 없습니다.")
            ProductSummary.of(product, brand, stock, likeCounts[product.id] ?: 0L)
        }
    }
}
