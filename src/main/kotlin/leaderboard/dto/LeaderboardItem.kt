package com.example.leaderboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaderboardItem(
    val rank: Int,
    val uid: String,
    val score: Int,
    val streak: Int,
    val bestStreak: Int,
    val league: String
)
