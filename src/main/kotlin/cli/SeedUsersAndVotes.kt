package com.example.cli

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.SetOptions
import com.google.firebase.cloud.FirestoreClient
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.random.Random

/**
 * Yerel emülatörler için sahte kullanıcı + ikilem + oy tohumlar.
 * PROD verisine dokunmaz; emülatör env yoksa yine de çalışır ama tavsiye edilmez.
 *
 * Çalıştırma:
 *   ./gradlew seedUsersAndVotes --args="users=50 dilemmas=30 votes=20-100"
 * Arg'lar opsiyoneldir (varsayılan: users=30, dilemmas=15, votes=10-60).
 */
fun main(args: Array<String>) {
    val params = args.toParams()
    val users = params["users"]?.toIntOrNull() ?: 30
    val dilemmas = params["dilemmas"]?.toIntOrNull() ?: 15
    val votesRange = (params["votes"] ?: "10-60").parseRange(10 to 60)

    initFirebaseAppIfNeeded()
    val db = FirestoreClient.getFirestore()

    println("==> Seeding: users=$users dilemmas=$dilemmas votesPerDilemma=${votesRange.first}-${votesRange.second}")

    val userIds = mutableListOf<String>()
    repeat(users) { i ->
        val uid = "u%04d".format(i + 1)
        userIds += uid
        val base = mapOf(
            "displayName" to "TestUser ${i + 1}",
            "score" to 0,
            "streak" to 0,
            "bestStreak" to 0,
            "createdAt" to System.currentTimeMillis(),
            "notificationsEnabled" to true
        )
        db.collection("users").document(uid).set(base).get()
    }
    println("Users seeded: ${userIds.size}")

    val utc = ZoneOffset.UTC
    val now = Instant.now()
    val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    val dilemmaIds = mutableListOf<String>()

    repeat(dilemmas) { i ->
        val created = now.minusSeconds(86400L * i)
        val dateStr = fmt.format(created.atZone(utc).toLocalDate())
        val startedAt = created.toEpochMilli()
        val expiresAt = startedAt + 20 * 60 * 60 * 1000

        val docRef = db.collection("dilemmas").add(
            mapOf(
                "title" to "Dilemma #${i + 1}",
                "date" to dateStr,
                "createdAt" to startedAt,
                "startedAt" to startedAt,
                "expiresAt" to expiresAt,
                "resolved" to (i % 4 == 0),
                "correctX" to (i % 8 == 0),
                "xCount" to 0,
                "yCount" to 0
            )
        ).get()
        val id = docRef.id
        db.collection("dilemmas").document(id)
            .set(mapOf("id" to id), SetOptions.merge())
            .get()
        dilemmaIds += id
    }
    println("Dilemmas seeded: ${dilemmaIds.size}")

    var totalVotes = 0
    val rnd = Random(System.currentTimeMillis())

    for (did in dilemmaIds) {
        val nVotes = rnd.nextInt(votesRange.first, votesRange.second + 1)
        val shuffled = userIds.shuffled(rnd).take(nVotes)

        var x = 0
        var y = 0
        for (uid in shuffled) {
            val choiceX = rnd.nextBoolean()
            if (choiceX) x++ else y++
            val conf = rnd.nextDouble(0.55, 0.98)

            val voteRef = db.collection("dilemmas").document(did)
                .collection("votes").document(uid)

            voteRef.set(
                mapOf(
                    "uid" to uid,
                    "choiceX" to choiceX,
                    "confidence" to conf,
                    "ts" to (System.currentTimeMillis() - rnd.nextLong(0, 72 * 3600 * 1000))
                )
            ).get()
        }

        db.collection("dilemmas").document(did).update(
            mapOf(
                "xCount" to x,
                "yCount" to y
            )
        ).get()
        totalVotes += nVotes
    }
    println("Votes seeded: $totalVotes")

    val res = db.collection("dilemmas")
        .whereEqualTo("resolved", true)
        .orderBy("createdAt", Query.Direction.DESCENDING)
        .limit(max(1, dilemmas / 3))
        .get().get()

    val winners = mutableMapOf<String, Int>()
    for (d in res.documents) {
        val correctX = d.getBoolean("correctX") == true
        val votesSnap = d.reference.collection("votes").get().get()
        for (v in votesSnap.documents) {
            val uid = v.getString("uid") ?: continue
            val choiceX = v.getBoolean("choiceX") == true
            val pts = if (choiceX == correctX) 5 else 0
            if (pts > 0) winners[uid] = (winners[uid] ?: 0) + pts
        }
    }
    for ((uid, add) in winners) {
        db.collection("users").document(uid)
            .update("score", FieldValue.increment(add.toLong()))
            .get()
    }
    println("Scores updated for ${winners.size} users. DONE.")
}

private fun Array<String>.toParams(): Map<String, String> {
    if (this.isEmpty()) return emptyMap()
    return this.joinToString(" ")
        .split(' ')
        .mapNotNull {
            val p = it.trim()
            if (p.isEmpty() || !p.contains('=')) null
            else p.substringBefore('=') to p.substringAfter('=')
        }.toMap()
}

private fun String.parseRange(default: Pair<Int, Int>): Pair<Int, Int> {
    val parts = this.split('-')
    return if (parts.size == 2) {
        val a = parts[0].toIntOrNull()
        val b = parts[1].toIntOrNull()
        if (a != null && b != null && a > 0 && b >= a) a to b else default
    } else default
}
