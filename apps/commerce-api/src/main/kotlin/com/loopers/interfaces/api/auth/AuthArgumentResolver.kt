package com.loopers.interfaces.api.auth

import com.loopers.domain.auth.AuthService
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * `@UserAuth` 파라미터를 만나면 인증 헤더를 검증해 userId 를 주입한다.
 * 기존 컨트롤러에 흩어져 있던 `authService.login(loginId, loginPw)` 호출을 한 곳으로 모은다.
 */
@Component
class AuthArgumentResolver(
    private val authService: AuthService,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(UserAuth::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Long {
        val loginId = webRequest.getHeader(HEADER_LOGIN_ID)
            ?: throw CoreException(ErrorType.BAD_REQUEST, "$HEADER_LOGIN_ID 헤더가 필요합니다.")
        val loginPw = webRequest.getHeader(HEADER_LOGIN_PW)
            ?: throw CoreException(ErrorType.BAD_REQUEST, "$HEADER_LOGIN_PW 헤더가 필요합니다.")
        return authService.login(loginId, loginPw)
    }

    companion object {
        const val HEADER_LOGIN_ID = "X-Loopers-LoginId"
        const val HEADER_LOGIN_PW = "X-Loopers-LoginPw"
    }
}
