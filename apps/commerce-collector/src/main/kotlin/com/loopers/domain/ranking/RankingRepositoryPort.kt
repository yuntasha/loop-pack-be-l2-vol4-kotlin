package com.loopers.domain.ranking

interface RankingRepositoryPort {
    /**
     * 한 이벤트의 모든 보드 갱신을 버전별 dedup 키(ranking:handled:{version}:{eventId}) 아래 원자적으로 반영한다.
     * dedup이 버전별이므로 배치 replay와 실시간 이중 적재가 같은 이벤트를 만나도 버전당 한 번만 반영된다.
     *
     * @return true = 실제 반영 / false = dedup에 의해 skip(점수가 이미 반영돼 있음).
     * false는 "Redis 반영 후 커밋 실패 → 재전송" 재시도의 정상 경로이므로 예외로 다루면 안 된다.
     */
    fun incrementScore(version: String, entries: List<BoardScore>, productId: Long, eventId: String): Boolean
}
