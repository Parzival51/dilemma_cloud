package com.example.dilemma.model

import kotlinx.serialization.Serializable

@Serializable
data class OptionStat(
    val id: String,
    val label: String,
    val count: Long,
    val pct: Double        // 0..100
)

@Serializable
data class StatsResponse(
    val x: Long,
    val y: Long,
    val pctX: Double,
    val pctY: Double,
    val options: List<OptionStat>? = null   // multi için
)
