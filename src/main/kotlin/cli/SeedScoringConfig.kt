package com.example.cli

import com.google.firebase.cloud.FirestoreClient

fun main() {
    initFirebaseAppIfNeeded()

    val db = FirestoreClient.getFirestore()
    val data = mapOf(
        "default" to "A",
        "variants" to mapOf(
            "A" to mapOf("alpha" to 0.5, "beta" to 1.0),
            "B" to mapOf("alpha" to 0.4, "beta" to 1.4)
        )
    )
    db.collection("config").document("scoring").set(data).get()
    println("✅ Seeded: config/scoring")
}
