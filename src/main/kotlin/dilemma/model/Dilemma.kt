package com.example.dilemma.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset

@Serializable
data class Dilemma(
    val id: String = "",
    val title: String = "",

    // Günlük dilemmalar için tarih key’i (getToday() bunu kullanıyor)
    val date: String = LocalDate.now(ZoneOffset.UTC).toString(),

    val startedAt: Long = 0L,
    val expiresAt: Long = 0L,

    // Binary toplam sayımlar (multi için de aggregate amaçlı kullanılabilir)
    val xCount: Long = 0,
    val yCount: Long = 0,

    // Eski pros/cons yapın – şimdilik dokunmuyoruz
    val prosCons: ProsCons? = null,

    // ---------- Submission’dan gelen metadata ----------

    // Kullanıcının tanımladığı buton label’ları (opsiyonel)
    val xLabel: String? = null,
    val yLabel: String? = null,

    // Tarafsız açıklama + kaynak linki
    val context: String? = null,
    val sourceUrl: String? = null,

    // Konu ve bölge
    val topic: String? = null,
    val region: String? = null,

    // Sınıflandırma alanları
    // Örn: "football", "politics", "crypto", "education", "daily-life", "general"
    val category: String = "general",

    // Örn: "prediction" | "advice" | "poll"
    val questionType: String = "prediction",

    // Örn: "tr", "en"
    val language: String = "tr",

    // Örn: "public" | "followers" | "private-link"
    val visibility: String = "public",

    // B2B / sponsorlu sorular için opsiyonel alan
    // Örn: bir marka kampanyası, ajans projesi vs.
    val sponsorId: String? = null
)
