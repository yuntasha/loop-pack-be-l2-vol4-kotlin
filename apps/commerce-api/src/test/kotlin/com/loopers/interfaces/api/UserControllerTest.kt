package com.loopers.interfaces.api

import com.loopers.domain.auth.AuthService
import com.loopers.domain.user.User
import com.loopers.domain.user.UserInfo
import com.loopers.interfaces.api.user.UserApplicationServicePort
import com.loopers.interfaces.api.user.UserController
import com.loopers.interfaces.api.waitingqueue.QueueApplicationServicePort
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDate

@WebMvcTest(UserController::class)
class UserControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var userApplicationService: UserApplicationServicePort

    // 전역 WebMvcConfig 가 슬라이스에 끌어오는 인증/대기열 웹 컴포넌트의 의존성 목킹
    @MockkBean
    private lateinit var authService: AuthService

    @MockkBean
    private lateinit var queueApplicationService: QueueApplicationServicePort

    @DisplayName("회원가입 API를 호출하면, UserFacade.signup이 호출된다.")
    @Test
    fun callsFacadeSignup_whenSignupApiIsCalled() {
        // arrange
        val savedUser = User(id = 1L, name = "홍길동", birth = LocalDate.of(1995, 3, 15), email = "test@example.com")
        every { userApplicationService.signup(any()) } returns savedUser

        // act
        mockMvc.post("/api/v1/user/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                    "id": "testuser01",
                    "pw": "Password1!",
                    "name": "홍길동",
                    "birth": "1995-03-15",
                    "email": "test@example.com"
                }
            """.trimIndent()
        }

        // assert
        verify(atLeast = 1) { userApplicationService.signup(any()) }
    }

    @DisplayName("유저 정보 조회 API를 호출할 때, ")
    @Nested
    inner class GetUserInfo {
        @DisplayName("인증에 성공하면, 유저 정보를 반환한다.")
        @Test
        fun returnsUserInfo_whenLoginSucceeds() {
            // arrange
            val loginId = "testuser01"
            val password = "Password1!"
            val info = UserInfo(
                loginId = loginId,
                name = "홍길동",
                birth = LocalDate.of(1995, 3, 15),
                email = "test@example.com",
            )
            every { userApplicationService.getMyInfo(loginId, password) } returns info

            // act & assert
            mockMvc.get("/api/v1/user/info") {
                header("X-Loopers-LoginId", loginId)
                header("X-Loopers-LoginPw", password)
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.loginId") { value("testuser01") }
                jsonPath("$.data.name") { value("홍길*") }
                jsonPath("$.data.birth") { value("1995-03-15") }
                jsonPath("$.data.email") { value("test@example.com") }
                jsonPath("$.meta.result") { value("SUCCESS") }
            }

            verify(exactly = 1) { userApplicationService.getMyInfo(loginId, password) }
        }

        @DisplayName("인증에 실패하면, 401 인증 예외가 발생한다.")
        @Test
        fun returnsUnauthorized_whenLoginFails() {
            // arrange
            val loginId = "wronguser"
            val password = "WrongPass1!"
            every { userApplicationService.getMyInfo(loginId, password) } throws CoreException(
                ErrorType.UNAUTHORIZED,
                "아이디 또는 비밀번호가 올바르지 않습니다.",
            )

            // act & assert
            mockMvc.get("/api/v1/user/info") {
                header("X-Loopers-LoginId", loginId)
                header("X-Loopers-LoginPw", password)
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.meta.result") { value("FAIL") }
                jsonPath("$.meta.errorCode") { value("Unauthorized") }
            }

            verify(exactly = 1) { userApplicationService.getMyInfo(loginId, password) }
        }
    }

    @DisplayName("비밀번호 변경 API를 호출할 때, ")
    @Nested
    inner class ChangePassword {
        @DisplayName("비밀번호 변경에 성공하면, 성공 응답을 반환한다.")
        @Test
        fun returnsSuccess_whenChangePasswordSucceeds() {
            // arrange
            every { userApplicationService.changePassword(any()) } just runs

            // act & assert
            mockMvc.put("/api/v1/user/password") {
                header("X-Loopers-LoginId", "testuser01")
                header("X-Loopers-LoginPw", "Password1!")
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                        "prevPw": "Password1!",
                        "nextPw": "NewPass1!!"
                    }
                """.trimIndent()
            }.andExpect {
                status { isOk() }
                jsonPath("$.meta.result") { value("SUCCESS") }
            }

            verify(exactly = 1) { userApplicationService.changePassword(any()) }
        }

        @DisplayName("인증에 실패하면, 401 인증 예외가 발생한다.")
        @Test
        fun returnsUnauthorized_whenAuthenticationFails() {
            // arrange
            every { userApplicationService.changePassword(any()) } throws CoreException(
                ErrorType.UNAUTHORIZED,
                "아이디 또는 비밀번호가 올바르지 않습니다.",
            )

            // act & assert
            mockMvc.put("/api/v1/user/password") {
                header("X-Loopers-LoginId", "testuser01")
                header("X-Loopers-LoginPw", "WrongPass1!")
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                        "prevPw": "WrongPass1!",
                        "nextPw": "NewPass1!!"
                    }
                """.trimIndent()
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.meta.result") { value("FAIL") }
                jsonPath("$.meta.errorCode") { value("Unauthorized") }
            }

            verify(exactly = 1) { userApplicationService.changePassword(any()) }
        }

        @DisplayName("잘못된 요청이면, 400 에러가 발생한다.")
        @Test
        fun returnsBadRequest_whenRequestIsInvalid() {
            // arrange
            every { userApplicationService.changePassword(any()) } throws CoreException(
                ErrorType.BAD_REQUEST,
                "비밀번호는 8~16자여야 합니다.",
            )

            // act & assert
            mockMvc.put("/api/v1/user/password") {
                header("X-Loopers-LoginId", "testuser01")
                header("X-Loopers-LoginPw", "Password1!")
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                        "prevPw": "Password1!",
                        "nextPw": "short"
                    }
                """.trimIndent()
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.result") { value("FAIL") }
                jsonPath("$.meta.errorCode") { value("Bad Request") }
            }

            verify(exactly = 1) { userApplicationService.changePassword(any()) }
        }
    }
}
