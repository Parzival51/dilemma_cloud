package com.example

import com.example.dilemma.routes.adminRoutes
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import com.example.dilemma.routes.dilemmaRoutes

fun Application.configureRouting() {

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

        val repo     = com.example.dilemma.repository.FirestoreDilemmaRepo(db)
        val service  = com.example.dilemma.service.DilemmaService(repo)


        dilemmaRoutes(service)
        adminRoutes(service)


        get("/ping") { call.respondText("pong") }

        post("/add") {
            val ts = System.currentTimeMillis()
            val ref = db.collection("tests").add(mapOf("ts" to ts)).get()
            call.respond(HttpStatusCode.Created, mapOf("id" to ref.id, "ts" to ts))
        }
    }
}
