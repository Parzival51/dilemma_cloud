package com.example.util

object TextSanitizer {
    private val zeroWidthRegex = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val spacesRegex = Regex("\\s+")
    private val urlRegex = Regex("(https?://\\S+|www\\.\\S+)", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", setOf(RegexOption.IGNORE_CASE))
    private val phoneRegex = Regex("\\+?\\d[\\d\\s()\\-]{8,}\\d")

    fun normalize(input: String): String {
        val t = input.trim()
        val noZW = zeroWidthRegex.replace(t, "")
        return spacesRegex.replace(noZW, " ")
    }

    fun maskSensitive(s: String): String =
        s.let { urlRegex.replace(it, "[link]") }
            .let { emailRegex.replace(it, "[email]") }
            .let { phoneRegex.replace(it, "[phone]") }

    fun maskProfanity(s: String): String {
        var out = s
        for (w in ProfanityRules.blocklist) {
            if (w.isBlank()) continue
            val pattern = Regex("\\b${Regex.escape(w)}\\b", RegexOption.IGNORE_CASE)
            out = out.replace(pattern) { m -> maskWord(m.value) }
        }
        return out
    }

    private fun maskWord(w: String): String =
        if (w.length <= 2) "*".repeat(w.length)
        else "${w.first()}${"*".repeat(w.length - 2)}${w.last()}"

    /** Reason metinleri için ortak sanitizasyon (≤400) */
    fun sanitizeReason(text: String): String {
        val cut = text.take(400)
        val base = normalize(cut)
        val masked = maskSensitive(base)
        return maskProfanity(masked)
    }

    fun sanitizeShortLabel(text: String): String = normalize(text).take(40)
    fun sanitizeTitle(text: String): String = normalize(text).take(120)

    fun requireLenBetween(s: String, min: Int, max: Int, field: String) {
        if (s.length < min || s.length > max) {
            throw IllegalArgumentException("$field length must be in [$min,$max]")
        }
    }
}
