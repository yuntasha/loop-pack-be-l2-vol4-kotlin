package com.loopers.interfaces.api.auth

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.method.HandlerMethod

class AdminAuthInterceptorTest {
    private val interceptor = AdminAuthInterceptor()
    private val response = mockk<HttpServletResponse>(relaxed = true)

    @AdminAuth
    class AdminAnnotatedController

    class PublicController

    private fun handlerOf(beanType: Class<*>): HandlerMethod {
        val handler = mockk<HandlerMethod>()
        every { handler.hasMethodAnnotation(AdminAuth::class.java) } returns false
        every { handler.beanType } returns beanType
        return handler
    }

    @Test
    fun `HandlerMethod 가 아니면 통과한다`() {
        assertThat(interceptor.preHandle(mockk(), response, Any())).isTrue()
    }

    @Test
    fun `@AdminAuth 가 없는 핸들러는 통과한다`() {
        val request = mockk<HttpServletRequest>()

        assertThat(interceptor.preHandle(request, response, handlerOf(PublicController::class.java))).isTrue()
    }

    @Test
    fun `@AdminAuth + 올바른 ldap 이면 통과한다`() {
        val request = mockk<HttpServletRequest>()
        every { request.getHeader(AdminAuthInterceptor.HEADER_LDAP) } returns AdminAuthInterceptor.ADMIN_LDAP

        assertThat(interceptor.preHandle(request, response, handlerOf(AdminAnnotatedController::class.java))).isTrue()
    }

    @Test
    fun `@AdminAuth + 잘못된 ldap 이면 FORBIDDEN 예외`() {
        val request = mockk<HttpServletRequest>()
        every { request.getHeader(AdminAuthInterceptor.HEADER_LDAP) } returns "someone.else"

        assertThatThrownBy {
            interceptor.preHandle(request, response, handlerOf(AdminAnnotatedController::class.java))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)
    }

    @Test
    fun `@AdminAuth + ldap 헤더 없으면 FORBIDDEN 예외`() {
        val request = mockk<HttpServletRequest>()
        every { request.getHeader(AdminAuthInterceptor.HEADER_LDAP) } returns null

        assertThatThrownBy {
            interceptor.preHandle(request, response, handlerOf(AdminAnnotatedController::class.java))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)
    }
}
