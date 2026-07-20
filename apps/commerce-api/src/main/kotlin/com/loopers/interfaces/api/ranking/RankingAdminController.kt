package com.loopers.interfaces.api.ranking

import com.loopers.interfaces.api.ApiResponse
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api-admin/v1/rankings/weights")
class RankingAdminController(
    private val rankingAdminApplicationService: RankingAdminApplicationServicePort,
) {
    @GetMapping
    fun getWeights(
        @RequestHeader(name = "X-Loopers-Ldap", required = false) ldap: String?,
    ): ApiResponse<List<RankingAdminV1Dto.WeightResponse>> {
        verifyAdmin(ldap)
        val results = rankingAdminApplicationService.getWeights()
        return ApiResponse.success(results.map(RankingAdminV1Dto.WeightResponse::from))
    }

    @PostMapping
    fun registerWeights(
        @RequestHeader(name = "X-Loopers-Ldap", required = false) ldap: String?,
        @RequestBody request: RankingAdminV1Dto.RegisterWeightRequest,
    ): ApiResponse<RankingAdminV1Dto.WeightResponse> {
        verifyAdmin(ldap)
        if (request.viewWeight < 1 || request.likeWeight < 1 || request.orderWeight < 1) {
            throw CoreException(ErrorType.BAD_REQUEST, "가중치는 1 이상이어야 합니다.")
        }
        val result = rankingAdminApplicationService.registerWeights(request.toCommand())
        return ApiResponse.success(RankingAdminV1Dto.WeightResponse.from(result))
    }

    @PostMapping("/{version}/activate")
    fun activateWeights(
        @RequestHeader(name = "X-Loopers-Ldap", required = false) ldap: String?,
        @PathVariable version: String,
    ): ApiResponse<RankingAdminV1Dto.WeightResponse> {
        verifyAdmin(ldap)
        val result = rankingAdminApplicationService.activateWeights(version)
        return ApiResponse.success(RankingAdminV1Dto.WeightResponse.from(result))
    }

    @PostMapping("/{version}/retire")
    fun retireWeights(
        @RequestHeader(name = "X-Loopers-Ldap", required = false) ldap: String?,
        @PathVariable version: String,
    ): ApiResponse<RankingAdminV1Dto.WeightResponse> {
        verifyAdmin(ldap)
        val result = rankingAdminApplicationService.retireWeights(version)
        return ApiResponse.success(RankingAdminV1Dto.WeightResponse.from(result))
    }

    @PostMapping("/{version}/reopen")
    fun reopenWeights(
        @RequestHeader(name = "X-Loopers-Ldap", required = false) ldap: String?,
        @PathVariable version: String,
    ): ApiResponse<RankingAdminV1Dto.WeightResponse> {
        verifyAdmin(ldap)
        val result = rankingAdminApplicationService.reopenWeights(version)
        return ApiResponse.success(RankingAdminV1Dto.WeightResponse.from(result))
    }

    private fun verifyAdmin(ldap: String?) {
        if (ldap != ADMIN_LDAP) {
            throw CoreException(ErrorType.FORBIDDEN, "어드민 권한이 없습니다.")
        }
    }

    companion object {
        private const val ADMIN_LDAP = "loopers.admin"
    }
}
