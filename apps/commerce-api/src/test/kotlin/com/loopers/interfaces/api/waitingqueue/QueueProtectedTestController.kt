package com.loopers.interfaces.api.waitingqueue

import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.auth.UserAuth
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대기열 인터셉터 E2E 검증용 보호 엔드포인트. 실제 업무 컨트롤러를 건드리지 않기 위해
 * 테스트 소스에만 두며, `@WaitingQueue` 가드를 통과하면 200 을 반환한다.
 * (test 소스의 @RestController 는 @SpringBootTest 컴포넌트 스캔으로 자동 등록된다.)
 */
@RestController
class QueueProtectedTestController {
    @GetMapping(PATH)
    @WaitingQueue(TOPIC)
    fun protectedEndpoint(
        @UserAuth userId: Long,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.success(mapOf("userId" to userId))

    companion object {
        const val PATH = "/test/queue-protected"
        const val TOPIC = "order"
    }
}
