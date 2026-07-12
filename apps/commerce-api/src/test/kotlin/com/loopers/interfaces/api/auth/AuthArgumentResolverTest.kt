package com.loopers.interfaces.api.auth

import com.loopers.domain.auth.AuthService
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.web.context.request.NativeWebRequest

class AuthArgumentResolverTest {
    private val authService = mockk<AuthService>()
    private val resolver = AuthArgumentResolver(authService)

    @Nested
    @DisplayName("supportsParameter")
    inner class SupportsParameter {
        @Test
        fun `@UserAuth 가 붙은 파라미터를 지원한다`() {
            val parameter = mockk<MethodParameter>()
            every { parameter.hasParameterAnnotation(UserAuth::class.java) } returns true

            assertThat(resolver.supportsParameter(parameter)).isTrue()
        }

        @Test
        fun `@UserAuth 가 없으면 지원하지 않는다`() {
            val parameter = mockk<MethodParameter>()
            every { parameter.hasParameterAnnotation(UserAuth::class.java) } returns false

            assertThat(resolver.supportsParameter(parameter)).isFalse()
        }
    }

    @Nested
    @DisplayName("resolveArgument")
    inner class ResolveArgument {
        @Test
        fun `헤더를 검증해 userId 를 반환한다`() {
            val webRequest = mockk<NativeWebRequest>()
            every { webRequest.getHeader(AuthArgumentResolver.HEADER_LOGIN_ID) } returns "loginId"
            every { webRequest.getHeader(AuthArgumentResolver.HEADER_LOGIN_PW) } returns "pw"
            every { authService.login("loginId", "pw") } returns 42L

            val result = resolver.resolveArgument(mockk(relaxed = true), null, webRequest, null)

            assertThat(result).isEqualTo(42L)
        }

        @Test
        fun `로그인 아이디 헤더가 없으면 BAD_REQUEST 예외`() {
            val webRequest = mockk<NativeWebRequest>()
            every { webRequest.getHeader(AuthArgumentResolver.HEADER_LOGIN_ID) } returns null
            every { webRequest.getHeader(AuthArgumentResolver.HEADER_LOGIN_PW) } returns "pw"

            assertThatThrownBy {
                resolver.resolveArgument(mockk(relaxed = true), null, webRequest, null)
            }.isInstanceOf(CoreException::class.java)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
        }

        @Test
        fun `로그인 비밀번호 헤더가 없으면 BAD_REQUEST 예외`() {
            val webRequest = mockk<NativeWebRequest>()
            every { webRequest.getHeader(AuthArgumentResolver.HEADER_LOGIN_ID) } returns "loginId"
            every { webRequest.getHeader(AuthArgumentResolver.HEADER_LOGIN_PW) } returns null

            assertThatThrownBy {
                resolver.resolveArgument(mockk(relaxed = true), null, webRequest, null)
            }.isInstanceOf(CoreException::class.java)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
        }
    }
}
