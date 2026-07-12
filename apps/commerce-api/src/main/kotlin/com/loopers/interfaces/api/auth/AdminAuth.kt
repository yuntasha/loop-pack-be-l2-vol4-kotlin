package com.loopers.interfaces.api.auth

/**
 * 어드민 권한 가드 어노테이션.
 *
 * 클래스 또는 메서드에 부착하면 `AdminAuthInterceptor` 가 `X-Loopers-Ldap` 헤더를 검증한다.
 * 권한이 없으면 403(FORBIDDEN). 컨트롤러마다 흩어져 있던 `verifyAdmin` 중복을 제거한다.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AdminAuth
