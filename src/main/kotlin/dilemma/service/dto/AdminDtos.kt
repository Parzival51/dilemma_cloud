package com.example.dilemma.service.dto

import kotlinx.serialization.Serializable

@Serializable
data class MultiSetupResponse(
    val id: String,
    val type: String,
    val options: List<OptionInput>
)

@Serializable
data class LeagueCuts(
    val eliteCut: Int,
    val goldCut: Int,
    val silverCut: Int
)

@Serializable
data class LeagueRecomputeResponse(
    val total: Int,
    val processed: Int,
    val writes: Int,
    val field: String,
    val cuts: LeagueCuts,
    val dryRun: Boolean
)
