package com.loopers.support.error

import org.springframework.http.HttpStatus

enum class ErrorType(val status: HttpStatus, val code: String, val message: String) {
    /** 범용 에러 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase, "일시적인 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.reasonPhrase, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, HttpStatus.UNAUTHORIZED.reasonPhrase, "인증에 실패했습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, HttpStatus.FORBIDDEN.reasonPhrase, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.reasonPhrase, "존재하지 않는 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, HttpStatus.CONFLICT.reasonPhrase, "이미 존재하는 리소스입니다."),
    TOO_MANY_REQUESTS(
        HttpStatus.TOO_MANY_REQUESTS,
        HttpStatus.TOO_MANY_REQUESTS.reasonPhrase,
        "요청이 많아 대기열에 등록되었습니다. 순번을 확인해주세요.",
    ),
    SERVICE_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        HttpStatus.SERVICE_UNAVAILABLE.reasonPhrase,
        "일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.",
    ),
}
