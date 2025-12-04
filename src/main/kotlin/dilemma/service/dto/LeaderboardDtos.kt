package com.example.dilemma.service.dto

import kotlinx.serialization.Serializable

@Serializable
data class LbItem(
    val rank: Int,
    val uid: String,
    val score: Int,
    val streak: Int,
    val bestStreak: Int
)

@Serializable
data class LbSnapshot(
    val items: List<LbItem>,
    val updatedAt: Long,
    val range: String,
    val count: Int
)