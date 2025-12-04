package com.example.dilemma.model

import kotlinx.serialization.Serializable

@Serializable
data class Vote(
    // Hangi kullanıcı oy vermiş
    val userId: String = "",

    // Binary için X mi Y mi? (multi durumda genelde kullanılmayacak)
    val choiceX: Boolean,

    // Oy verme zamanı (ms)
    val ts: Long = System.currentTimeMillis(),

    // ---- Multi-choice & kalibrasyon alanları ----

    // Multi için: seçilen option id'si (binary'de null kalır)
    val optionId: String? = null,

    // 0..1 arası güven skoru (önerilen aralık 0.55..0.99)
    val confidence: Double? = null,

    // Kullanıcının kısa açıklaması (≤120 karakter; "Diğer/Hiçbiri" metni de buraya gelebilir)
    val reason: String? = null,

    // A/B testleri için varyant bilgisi ("A", "B", "grid", "donut" vs.)
    val variant: String? = null,

    // ---- Resolve / scoring alanları ----

    // Dilemma çözülmüş mü? (sonuç işlenmiş mi?)
    val resolved: Boolean = false,

    // Bu oy doğru muydu? (resolved=true ise dolmaya başlar)
    val correct: Boolean? = null,

    // Bu oydan kaç puan kazandı? (resolved=true ise 0 da olabilir)
    val points: Int? = null
)
