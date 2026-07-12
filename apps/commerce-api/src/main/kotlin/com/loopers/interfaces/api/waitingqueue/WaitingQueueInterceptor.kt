package com.loopers.interfaces.api.waitingqueue

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.waitingqueue.EnterCommand
import com.loopers.application.waitingqueue.VerifyCommand
import com.loopers.application.waitingqueue.WaitTokenResult
import com.loopers.domain.auth.AuthService
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.auth.AuthArgumentResolver
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * `@WaitingQueue` 가 붙은 API 를 가로채 대기열로 보호한다.
 *
 * 1. 유효한 입장 토큰(X-Queue-Access-Token)이 있으면 통과시킨다.
 * 2. 없으면 대기열에 (재)등록하고 `429 Too Many Requests` + 대기열 토큰을 응답한다.
 * userId 추출은 `@UserAuth` 와 동일한 헤더 규칙([AuthArgumentResolver])을 재사용한다.
 */
@Component
class WaitingQueueInterceptor(
    private val queueApplicationService: QueueApplicationServicePort,
    private val authService: AuthService,
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true

        val waitingQueue = handler.getMethodAnnotation(WaitingQueue::class.java)
            ?: handler.beanType.getAnnotation(WaitingQueue::class.java)
            ?: return true

        val accessToken = request.getHeader(HEADER_ACCESS_TOKEN)
        if (accessToken != null &&
            queueApplicationService.verifyAccess(VerifyCommand(accessToken, waitingQueue.topic))
        ) {
            return true
        }

        val userId = extractUserId(request)
        val result = queueApplicationService.enter(EnterCommand(topic = waitingQueue.topic, userId = userId))
        writeQueued(response, result)
        return false
    }

    private fun extractUserId(request: HttpServletRequest): Long {
        val loginId = request.getHeader(AuthArgumentResolver.HEADER_LOGIN_ID)
            ?: throw CoreException(ErrorType.UNAUTHORIZED, "인증이 필요합니다.")
        val loginPw = request.getHeader(AuthArgumentResolver.HEADER_LOGIN_PW)
            ?: throw CoreException(ErrorType.UNAUTHORIZED, "인증이 필요합니다.")
        return authService.login(loginId, loginPw)
    }

    private fun writeQueued(response: HttpServletResponse, result: WaitTokenResult) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        val body = ApiResponse(
            meta = ApiResponse.Metadata.fail(
                errorCode = ErrorType.TOO_MANY_REQUESTS.code,
                errorMessage = ErrorType.TOO_MANY_REQUESTS.message,
            ),
            data = WaitingQueueV1Dto.EnterResponse.from(result),
        )
        objectMapper.writeValue(response.writer, body)
    }

    companion object {
        const val HEADER_ACCESS_TOKEN = "X-Queue-Access-Token"
    }
}
