package com.loopers.interfaces.api.auth

/**
 * 대고객 인증 파라미터 주입 어노테이션.
 *
 * `X-Loopers-LoginId` / `X-Loopers-LoginPw` 헤더를 검증(`AuthService.login`)해
 * `userId(Long)` 를 컨트롤러 파라미터로 주입한다. 토큰 발급 없이 기존 헤더 검증 로직을 재사용한다.
 *
 * ```kotlin
 * fun issueCoupon(@UserAuth userId: Long, ...) { ... }
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class UserAuth
