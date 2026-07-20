package com.loopers.application.ranking

/** 가중치 등록 유스케이스 입력. 가중치는 논리값(예: VIEW 1) — 저장 스케일(×10) 변환은 도메인 책임이다. */
data class RegisterRankingWeightCommand(
    val version: String,
    val viewWeight: Long,
    val likeWeight: Long,
    val orderWeight: Long,
)
