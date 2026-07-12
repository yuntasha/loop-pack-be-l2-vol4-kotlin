package com.loopers.interfaces.api.waitingqueue

import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.auth.AdminAuth
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대기열 설정 관리자 API. 토픽별 폴링 주기/승격 인원/TTL 을 조정한다.
 * @AdminAuth 로 X-Loopers-Ldap 권한을 검증한다(없으면 403).
 */
@RestController
@AdminAuth
@RequestMapping("/api-admin/v1/queue")
class QueueAdminController(
    private val queueAdminApplicationService: QueueAdminApplicationServicePort,
) {
    @PutMapping("/{topic}/config")
    fun updateConfig(
        @PathVariable topic: String,
        @RequestBody request: QueueAdminV1Dto.UpdateConfigRequest,
    ): ApiResponse<QueueAdminV1Dto.ConfigResponse> {
        val result = queueAdminApplicationService.updateConfig(request.toCommand(topic))
        return ApiResponse.success(QueueAdminV1Dto.ConfigResponse.from(result))
    }

    @GetMapping("/{topic}/config")
    fun getConfig(
        @PathVariable topic: String,
    ): ApiResponse<QueueAdminV1Dto.ConfigResponse> {
        val result = queueAdminApplicationService.getConfig(topic)
        return ApiResponse.success(QueueAdminV1Dto.ConfigResponse.from(result))
    }
}
