package com.loopers.interfaces.api.waitingqueue

/**
 * 이 어노테이션이 붙은 API 는 대기열로 보호된다.
 * `topic` 단위로 대기열/설정/토큰이 분리된다. (`@WaitingQueue("order")` 등)
 * 클래스에 붙이면 해당 컨트롤러의 모든 핸들러에 적용된다.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WaitingQueue(
    val topic: String,
)
