// file: src/main/kotlin/com/example/notifications/NotificationService.kt
package com.example.notifications

import com.google.firebase.cloud.FirestoreClient
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.ApnsFcmOptions
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import java.time.Instant

object NotificationService {

    /** Günlük “Günün İkilemi” bildirimi */
    fun sendDaily(uid: String, fcmToken: String) {
        val notif = Notification
            .builder()
            .setTitle("Günün İkilemi hazır")
            .setBody("Bugünün ikilemine göz at ve oy ver!")
            .build()

        val msg = Message
            .builder()
            .setToken(fcmToken)
            .setNotification(notif)
            .putData("type", "daily")
            .putData("uid", uid)
            .setAndroidConfig(
                AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .build()
            )
            .setApnsConfig(
                ApnsConfig.builder()
                    .setAps(Aps.builder().setSound("default").build())
                    .setFcmOptions(
                        ApnsFcmOptions.builder()
                            .setAnalyticsLabel("daily_dilemma")
                            .build()
                    )
                    .build()
            )
            .build()

        FirebaseMessaging.getInstance().sendAsync(msg)
    }

    /** Submission durum bildirimi (mevcut mantık korunur) */
    fun sendSubmissionStatus(uid: String, status: String, title: String) {
        val db = FirestoreClient.getFirestore()
        val tokensSnap = db.collection("users")
            .document(uid)
            .collection("fcmTokens")
            .whereEqualTo("enabled", true)
            .get().get()
        if (tokensSnap.isEmpty) return

        val (notifTitle, notifBody) = if (status == "approved") {
            "Önerin yayınlandı!" to "‘$title’ onaylandı ve sıraya alındı."
        } else {
            "Önerin reddedildi" to "‘$title’ uygun bulunmadı."
        }

        val notif = Notification.builder()
            .setTitle(notifTitle)
            .setBody(notifBody)
            .build()

        tokensSnap.documents.mapNotNull { it.id }.forEach { token ->
            val msg = Message.builder()
                .setToken(token)
                .setNotification(notif)
                .putData("type", "submission_status")
                .putData("status", status)
                .putData("title", title)
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build()
                )
                .setApnsConfig(
                    ApnsConfig.builder()
                        .setAps(Aps.builder().setSound("default").build())
                        .setFcmOptions(
                            ApnsFcmOptions.builder()
                                .setAnalyticsLabel("submission_status")
                                .build()
                        )
                        .build()
                )
                .build()

            FirebaseMessaging.getInstance().sendAsync(msg)
        }

        db.collection("users").document(uid).set(
            mapOf("lastSubmissionStatusNotifiedAt" to Instant.now().toEpochMilli()),
            com.google.cloud.firestore.SetOptions.merge()
        )
    }

    /** Çözülmüş ikilemler için sonuç bildirimi (M3.1) */
    fun sendResult(uid: String, title: String, correct: Boolean, points: Int) {
        val db = FirestoreClient.getFirestore()
        val tokensSnap = db.collection("users")
            .document(uid)
            .collection("fcmTokens")
            .whereEqualTo("enabled", true)
            .get().get()
        if (tokensSnap.isEmpty) return

        val (notifTitle, notifBody) = if (correct) {
            val body = if (points > 0) {
                "Tahminin doğru çıktı, $points puan kazandın."
            } else {
                "Tahminin doğru çıktı!"
            }
            "Sonuç açıklandı: Doğru bildin" to "‘$title’ için $body"
        } else {
            val body = if (points > 0) {
                "Bu sefer tutmadı, $points puan kaybettin."
            } else {
                "Bu sefer tahminin tutmadı."
            }
            "Sonuç açıklandı: Bu sefer olmadı" to "‘$title’ için $body"
        }

        val notif = Notification
            .builder()
            .setTitle(notifTitle)
            .setBody(notifBody)
            .build()

        tokensSnap.documents
            .mapNotNull { it.id }
            .forEach { token ->
                val msg = Message
                    .builder()
                    .setToken(token)
                    .setNotification(notif)
                    .putData("type", "result")
                    .putData("title", title)
                    .putData("correct", correct.toString())
                    .putData("points", points.toString())
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build()
                    )
                    .setApnsConfig(
                        ApnsConfig.builder()
                            .setAps(Aps.builder().setSound("default").build())
                            .setFcmOptions(
                                ApnsFcmOptions.builder()
                                    .setAnalyticsLabel("result_notification")
                                    .build()
                            )
                            .build()
                    )
                    .build()

                FirebaseMessaging.getInstance().sendAsync(msg)
            }

        db.collection("users").document(uid).set(
            mapOf("lastResultNotifiedAt" to Instant.now().toEpochMilli()),
            com.google.cloud.firestore.SetOptions.merge()
        ).get()
    }
}
