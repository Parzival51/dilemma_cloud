package com.example.dilemma.model

import kotlinx.serialization.Serializable

@Serializable
data class DilemmaStats(
    val xCount: Long,
    val yCount: Long
)
