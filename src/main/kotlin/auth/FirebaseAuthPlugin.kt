package com.example.auth

import com.google.firebase.auth.FirebaseAuth
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.util.*

/* Ortak attribute key’ler başka dosyada tanımlı:
   UID_ATTR / IS_ADMIN_ATTR  (aynı package’ta olduğu için direkt kullanılıyor) */

class FirebaseAuthPlugin private constructor(
    private val optional: Boolean
) {
    class Configuration {
        var optional: Boolean = false
    }

    companion object : BaseApplicationPlugin<Application, Configuration, FirebaseAuthPlugin> {

        override val key = AttributeKey<FirebaseAuthPlugin>("FirebaseAuth")

        override fun install(
            pipeline: Application,
            configure: Configuration.() -> Unit
        ): FirebaseAuthPlugin {

            val cfg = Configuration().apply(configure)
            val plugin = FirebaseAuthPlugin(cfg.optional)

            pipeline.intercept(ApplicationCallPipeline.Plugins) {

                val idToken = call.request.headers["Authorization"]
                    ?.substringAfter("Bearer ")
                    ?.trim()

                if (idToken.isNullOrBlank()) {
                    if (plugin.optional) return@intercept
                    call.respond(HttpStatusCode.Unauthorized, "Missing token")
                    finish(); return@intercept
                }

                try {
                    val decoded = FirebaseAuth.getInstance().verifyIdToken(idToken)
                    call.attributes.put(UID_ATTR, decoded.uid)
                    call.attributes.put(IS_ADMIN_ATTR, decoded.claims["admin"] == true)

                } catch (e: Exception) {
                    // 🔎 Neden 401 verildiğini log’la
                    call.application.environment.log.warn(
                        "Auth verify failed: ${e.javaClass.simpleName}: ${e.message}"
                    )
                    call.respond(HttpStatusCode.Unauthorized, "Invalid token")
                    finish(); return@intercept
                }
            }
            return plugin
        }
    }
}
