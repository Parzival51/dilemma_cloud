package com.example.cli

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.File

internal fun initFirebaseAppIfNeeded() {
    if (FirebaseApp.getApps().isNotEmpty()) return

    val creds = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")
        ?.let { File(it).inputStream().use(GoogleCredentials::fromStream) }
        ?: GoogleCredentials.getApplicationDefault()

    val projectId = System.getenv("GOOGLE_CLOUD_PROJECT") ?: "gunun-ikilemi"

    FirebaseApp.initializeApp(
        FirebaseOptions.builder()
            .setCredentials(creds)
            .setProjectId(projectId)
            .build()
    )
}
