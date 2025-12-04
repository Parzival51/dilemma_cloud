package com.example.dilemma.service.dto

import kotlinx.serialization.Serializable

@Serializable
data class OptionDto(
    val id: String,
    val label: String,
    val count: Long = 0L
)

@Serializable
data class RecentDilemmaDto(
    val id: String,
    val title: String,
    val date: String = "",
    val startedAt: Long = 0L,
    val expiresAt: Long = 0L,
    val xCount: Long = 0L,
    val yCount: Long = 0L,
    val likesCount: Int = 0,
    val commentsCount: Int = 0,
    val likedByMe: Boolean = false,
    val type: String = "binary",          // "binary" | "multi"
    val options: List<OptionDto> = emptyList(),

    // ---- Metadata (Feed filtreleme & matchmaking için) ----
    val category: String = "general",          // örn: "football", "politics", "crypto"
    val questionType: String = "prediction",   // "prediction" | "advice" | "poll"
    val language: String = "tr",               // içerik dili
    val region: String? = null,                // ülke / bölge kodu
    val visibility: String = "public",         // "public" | ileride "private" / "unlisted"

    // Yeni alanlar (opsiyonel)
    val resolved: Boolean = false,
    val correctX: Boolean? = null,        // binary için (resolved=true ise dolu)
    val correctId: String? = null,        // multi için (resolved=true ise dolu)
    val myPoints: Int? = null             // kullanıcı oy verdiyse ve resolved=true ise dolu (0 olabilir)
)
