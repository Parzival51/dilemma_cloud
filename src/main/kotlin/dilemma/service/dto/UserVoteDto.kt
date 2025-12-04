package com.example.dilemma.service.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserVoteDto(
    val dilemmaId: String,
    val choiceX: Boolean,
    val ts: Long,
    val confidence: Double? = null,
    val reason: String? = null
)