package com.loopers.interfaces.api.ranking

import com.loopers.application.ranking.RankingPageCommand
import com.loopers.interfaces.api.ApiResponse
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/v1/rankings")
class RankingController(
    private val rankingApplicationService: RankingApplicationServicePort,
) {
    @GetMapping
    fun getRankings(
        @RequestParam(name = "date", required = false) date: String?,
        @RequestParam(name = "page", defaultValue = "1") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ): ApiResponse<RankingV1Dto.RankingPageResponse> {
        if (page < 1) {
            throw CoreException(ErrorType.BAD_REQUEST, "page는 1 이상이어야 합니다.")
        }
        if (size !in 1..MAX_PAGE_SIZE) {
            throw CoreException(ErrorType.BAD_REQUEST, "size는 1~${MAX_PAGE_SIZE} 사이여야 합니다.")
        }
        val parsedDate = date?.let {
            runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }
                .getOrElse { throw CoreException(ErrorType.BAD_REQUEST, "date는 yyyyMMdd 형식이어야 합니다.") }
        }

        val result = rankingApplicationService.getRankingPage(RankingPageCommand(date = parsedDate, page = page, size = size))
        return ApiResponse.success(RankingV1Dto.RankingPageResponse.from(result))
    }

    companion object {
        private const val MAX_PAGE_SIZE = 100
    }
}
