package com.example

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.event.Level
import java.io.File

fun Application.configureRouting() {

    // ───── Firebase Başlat ─────
    if (FirebaseApp.getApps().isEmpty()) {
        val creds = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")
            ?.let { File(it).inputStream().use(GoogleCredentials::fromStream) }
            ?: GoogleCredentials.getApplicationDefault()

        val projectId = System.getenv("GOOGLE_CLOUD_PROJECT") ?: "gunun-ikilemi"

        val opts = FirebaseOptions.builder()
            .setCredentials(creds)
            .setProjectId(projectId)
            .build()

        FirebaseApp.initializeApp(opts)
    }

    val db = FirestoreClient.getFirestore()


    routing {
        get("/ping") { call.respondText("pong") }

        post("/add") {
            val ts = System.currentTimeMillis()
            val ref = db.collection("tests").add(mapOf("ts" to ts)).get()
            call.respond(HttpStatusCode.Created, mapOf("id" to ref.id, "ts" to ts))
        }
    }
}
