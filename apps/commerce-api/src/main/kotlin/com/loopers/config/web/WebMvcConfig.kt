package com.loopers.config.web

import com.loopers.interfaces.api.auth.AdminAuthInterceptor
import com.loopers.interfaces.api.auth.AuthArgumentResolver
import com.loopers.interfaces.api.waitingqueue.WaitingQueueInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 웹 계층 횡단 관심사(인증 어노테이션, 대기열 가드 등) 전역 등록.
 */
@Configuration
class WebMvcConfig(
    private val authArgumentResolver: AuthArgumentResolver,
    private val adminAuthInterceptor: AdminAuthInterceptor,
    private val waitingQueueInterceptor: WaitingQueueInterceptor,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(authArgumentResolver)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(adminAuthInterceptor)
        registry.addInterceptor(waitingQueueInterceptor)
    }
}
