package com.example.dilemma.model

import kotlinx.serialization.Serializable

@Serializable
data class ProsCons(
    val xPros: List<String>,
    val xCons: List<String>,
    val yPros: List<String>,
    val yCons: List<String>
)
