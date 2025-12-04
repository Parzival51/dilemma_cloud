package com.example.dilemma.service.dto

import kotlinx.serialization.Serializable

@Serializable
data class StandingNear(
    val uid: String,
    val score: Int
)

@Serializable
data class StandingProgress(
    val pointsNeeded: Int,
    val targetUid: String,
    val targetScore: Int
)

@Serializable
data class StandingResponse(
    val range: String,
    val rank: Int,
    val score: Int,
    val league: String,
    val near: List<StandingNear>,
    val progressToNext: StandingProgress? = null
)
