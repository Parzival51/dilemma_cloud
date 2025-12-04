// file: src/main/kotlin/com/example/submission/model/Submission.kt
package com.example.submission.model

import kotlinx.serialization.Serializable

@Serializable
data class Submission(
    val id: String = "",
    val title: String = "",

    val xLabel: String? = null,
    val yLabel: String? = null,
    val context: String? = null,     // tarafsız kısa açıklama
    val sourceUrl: String? = null,   // haber/bağlam linki (opsiyonel)

    val status: String = "draft",    // draft | pending | approved | rejected
    val submitterId: String = "",

    val createdAt: Long = System.currentTimeMillis(),
    val submittedAt: Long? = null,
    val reviewedAt: Long? = null,
    val reviewerId: String? = null,
    val rejectReason: String? = null,

    val topic: String? = null,
    val region: String? = null,

    // Yeni metadata alanları
    val category: String? = null,        // football / crypto / politics / education...
    val questionType: String? = null,    // prediction | advice | poll
    val language: String? = null,        // "tr", "en", "tr-tr"...
    val visibility: String? = null       // public | private | unlisted
)
