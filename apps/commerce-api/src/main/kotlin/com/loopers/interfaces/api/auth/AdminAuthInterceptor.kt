package com.loopers.interfaces.api.auth

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * `@AdminAuth` 가 붙은 핸들러(메서드/클래스)에 대해 `X-Loopers-Ldap` 헤더를 검증한다.
 * 기존 어드민 컨트롤러마다 복제돼 있던 verifyAdmin 로직을 한 곳으로 모은다.
 */
@Component
class AdminAuthInterceptor : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true

        val requiresAdmin = handler.hasMethodAnnotation(AdminAuth::class.java) ||
            handler.beanType.isAnnotationPresent(AdminAuth::class.java)
        if (!requiresAdmin) return true

        val ldap = request.getHeader(HEADER_LDAP)
        if (ldap != ADMIN_LDAP) {
            throw CoreException(ErrorType.FORBIDDEN, "어드민 권한이 없습니다.")
        }
        return true
    }

    companion object {
        const val HEADER_LDAP = "X-Loopers-Ldap"
        const val ADMIN_LDAP = "loopers.admin"
    }
}
