// file: src/main/kotlin/com/example/ApplicationCors.kt
package com.example

import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.http.*

fun Application.configureCors() = install(CORS) {
    val webOrigin = System.getenv("WEB_ORIGIN") ?: "http://localhost:5173"
    val expected = webOrigin.removeSuffix("/").lowercase()

    // ✅ Port’lu origin’ler için esnek kontrol
    allowOrigins { origin ->
        val o = origin.removeSuffix("/").lowercase()
        // Tam eşleşme
        if (o == expected) return@allowOrigins true
        // Lokal geliştirme varyasyonları
        if ((o.startsWith("http://localhost:") || o.startsWith("http://127.0.0.1:"))
            && (o.endsWith(":5173") || o.endsWith(":3000"))) return@allowOrigins true
        false
    }

    // Tarayıcıdan gönderilen başlıklar
    allowHeader(HttpHeaders.Authorization)
    allowHeader(HttpHeaders.ContentType)
    allowHeader("X-Exp-Group")

    // Yöntemler
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Options)

    allowCredentials = true
    allowNonSimpleContentTypes = true
    maxAgeInSeconds = 24 * 60 * 60
}
