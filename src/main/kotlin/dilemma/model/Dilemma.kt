package com.example.dilemma.model

import kotlinx.serialization.Serializable

@Serializable
data class Dilemma(
    val id: String = "",
    val title: String,
    val startedAt: Long,
    val expiresAt: Long,
    val prosCons: ProsCons? = null
)
