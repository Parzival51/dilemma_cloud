// path: src/main/kotlin/com/example/dilemma/model/VoteBody.kt
package com.example.dilemma.model

import kotlinx.serialization.Serializable

@Serializable
internal data class VoteBody(
    // Binary için:
    val choiceX: Boolean? = null,

    // Multi için:
    val optionId: String? = null,

    // Ortak
    val confidence: Double? = null,   // 0.55..0.99 arası önerilir
    val reason: String? = null,       // ≤120 karakter (Diğer/Hiçbiri metnini burada taşıyacağız)
    val variant: String? = null       // A/B varyantı ("A","B"...)
)
