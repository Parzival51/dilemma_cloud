package com.example.dilemma.service.dto

import kotlinx.serialization.Serializable

@Serializable
data class OptionInput(
    val id: String,
    val label: String
)

@Serializable
data class MultiSetupBody(
    val options: List<OptionInput>,
    val withCounts: Boolean = false   // true ise optionCounts: {id:0,...} yazar
)
