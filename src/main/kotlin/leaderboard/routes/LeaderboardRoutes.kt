package com.example.leaderboard.routes

import com.example.leaderboard.dto.LeaderboardItem
import com.google.cloud.firestore.Query
import com.google.firebase.cloud.FirestoreClient
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Duration
import java.time.Instant
import kotlin.math.max

fun Route.leaderboardRoutes() {

    // Üst yüzdeye göre basit lig kuralı
    fun leagueFromTopPct(pTop: Double): String {
        val p = pTop.coerceIn(0.0, 1.0)
        return when {
            p >= 0.95 -> "elite"
            p >= 0.75 -> "gold"
            p >= 0.40 -> "silver"
            else      -> "bronze"
        }
    }

    get("/leaderboard") {
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val range = (call.request.queryParameters["range"] ?: "all").lowercase()
        // "true"/"false" dışındaki değerler null döner → varsayılan true
        val preferCache = call.request.queryParameters["preferCache"]?.toBooleanStrictOrNull() ?: true

        val db = FirestoreClient.getFirestore()

        when (range) {
            // ---------- DAY ----------
            "day" -> {
                // 1) Cache (≤15 dk taze ise)
                if (preferCache) {
                    val cacheDoc = db.collection("leaderboards").document("day").get().get()
                    if (cacheDoc.exists()) {
                        val updatedAt = cacheDoc.getLong("updatedAt") ?: 0L
                        val freshMs = Duration.ofMinutes(15).toMillis()
                        if (System.currentTimeMillis() - updatedAt <= freshMs) {
                            val itemsAny = cacheDoc.get("items")
                            val raw: List<Map<String, Any?>> = when (itemsAny) {
                                is List<*> -> itemsAny.mapNotNull { it as? Map<*, *> }.map { m ->
                                    mapOf(
                                        "rank" to (m["rank"] as? Number)?.toInt(),
                                        "uid" to (m["uid"] as? String),
                                        "score" to (m["score"] as? Number)?.toInt(),
                                        "streak" to (m["streak"] as? Number)?.toInt(),
                                        "bestStreak" to (m["bestStreak"] as? Number)?.toInt()
                                    )
                                }
                                else -> emptyList()
                            }
                            val total = max(1, raw.size)
                            val items: List<LeaderboardItem> = raw.map { m ->
                                val rankV = (m["rank"] as? Int) ?: 0
                                val pTop = if (rankV > 0) 1.0 - ((rankV - 1).toDouble() / total.toDouble()) else 0.0
                                LeaderboardItem(
                                    rank = rankV,
                                    uid = (m["uid"] as? String) ?: "",
                                    score = (m["score"] as? Int) ?: 0,
                                    streak = (m["streak"] as? Int) ?: 0,
                                    bestStreak = (m["bestStreak"] as? Int) ?: 0,
                                    league = leagueFromTopPct(pTop)
                                )
                            }
                            call.respond(items.take(limit))
                            return@get
                        }
                    }
                }

                // 2) Canlı hesap (son 24 saat)
                val now = Instant.now()
                val start = now.minus(Duration.ofDays(1)).toEpochMilli()

                // ⬇⬇⬇ İNDEKS GEREKTİRMEYEN SÜRÜM: resolved filtresi bellekte uygulanıyor
                val votesDocs = db.collectionGroup("votes")
                    .whereGreaterThanOrEqualTo("ts", start)
                    .whereLessThan("ts", now.toEpochMilli())
                    .orderBy("ts", Query.Direction.DESCENDING)
                    .get().get().documents

                val votes = votesDocs.filter { it.getBoolean("resolved") == true }

                val totals = mutableMapOf<String, Int>() // uid -> gün puanı
                votes.forEach { v ->
                    val uid = v.getString("uid") ?: return@forEach
                    val pts = (v.getLong("points") ?: 0L).toInt()
                    if (pts > 0) totals[uid] = (totals[uid] ?: 0) + pts
                }

                val sorted = totals.entries.sortedByDescending { it.value }.take(limit)
                val total = max(1, sorted.size)

                var rank = 0
                val items: List<LeaderboardItem> = sorted.map { (uid, score) ->
                    val u = db.collection("users").document(uid).get().get()
                    rank += 1
                    val pTop = 1.0 - ((rank - 1).toDouble() / total.toDouble())
                    LeaderboardItem(
                        rank = rank,
                        uid = uid,
                        score = score,
                        streak = (u.getLong("streak") ?: 0L).toInt(),
                        bestStreak = (u.getLong("bestStreak") ?: 0L).toInt(),
                        league = leagueFromTopPct(pTop)
                    )
                }

                call.respond(items)
            }

            // ---------- WEEK ----------
            "week" -> {
                // 1) Cache (≤15 dk taze ise)
                if (preferCache) {
                    val cacheDoc = db.collection("leaderboards").document("week").get().get()
                    if (cacheDoc.exists()) {
                        val updatedAt = cacheDoc.getLong("updatedAt") ?: 0L
                        val freshMs = Duration.ofMinutes(15).toMillis()
                        if (System.currentTimeMillis() - updatedAt <= freshMs) {
                            val itemsAny = cacheDoc.get("items")
                            val raw: List<Map<String, Any?>> = when (itemsAny) {
                                is List<*> -> itemsAny.mapNotNull { it as? Map<*, *> }.map { m ->
                                    mapOf(
                                        "rank" to (m["rank"] as? Number)?.toInt(),
                                        "uid" to (m["uid"] as? String),
                                        "score" to (m["score"] as? Number)?.toInt(),
                                        "streak" to (m["streak"] as? Number)?.toInt(),
                                        "bestStreak" to (m["bestStreak"] as? Number)?.toInt()
                                    )
                                }
                                else -> emptyList()
                            }
                            val total = max(1, raw.size)
                            val items: List<LeaderboardItem> = raw.map { m ->
                                val rankV = (m["rank"] as? Int) ?: 0
                                val pTop = if (rankV > 0) 1.0 - ((rankV - 1).toDouble() / total.toDouble()) else 0.0
                                LeaderboardItem(
                                    rank = rankV,
                                    uid = (m["uid"] as? String) ?: "",
                                    score = (m["score"] as? Int) ?: 0,
                                    streak = (m["streak"] as? Int) ?: 0,
                                    bestStreak = (m["bestStreak"] as? Int) ?: 0,
                                    league = leagueFromTopPct(pTop)
                                )
                            }
                            call.respond(items.take(limit))
                            return@get
                        }
                    }
                }

                // 2) Canlı hesap (son 7 gün)
                val now = Instant.now()
                val start = now.minus(Duration.ofDays(7)).toEpochMilli()

                // ⬇⬇⬇ İNDEKS GEREKTİRMEYEN SÜRÜM
                val votesDocs = db.collectionGroup("votes")
                    .whereGreaterThanOrEqualTo("ts", start)
                    .whereLessThan("ts", now.toEpochMilli())
                    .orderBy("ts", Query.Direction.DESCENDING)
                    .get().get().documents

                val votes = votesDocs.filter { it.getBoolean("resolved") == true }

                val totals = mutableMapOf<String, Int>() // uid -> hafta puanı
                votes.forEach { v ->
                    val uid = v.getString("uid") ?: return@forEach
                    val pts = (v.getLong("points") ?: 0L).toInt()
                    if (pts > 0) totals[uid] = (totals[uid] ?: 0) + pts
                }

                val sorted = totals.entries.sortedByDescending { it.value }.take(limit)
                val total = max(1, sorted.size)

                var rank = 0
                val items: List<LeaderboardItem> = sorted.map { (uid, score) ->
                    val u = db.collection("users").document(uid).get().get()
                    rank += 1
                    val pTop = 1.0 - ((rank - 1).toDouble() / total.toDouble())
                    LeaderboardItem(
                        rank = rank,
                        uid = uid,
                        score = score, // hafta puanı
                        streak = (u.getLong("streak") ?: 0L).toInt(),
                        bestStreak = (u.getLong("bestStreak") ?: 0L).toInt(),
                        league = leagueFromTopPct(pTop)
                    )
                }

                call.respond(items)
            }

            // ---------- ALL (users.score üzerinden) ----------
            else -> {
                val usersCol = db.collection("users")
                val docs = usersCol
                    .orderBy("score", Query.Direction.DESCENDING)
                    .limit(limit)
                    .get().get().documents

                val totalCount = usersCol.count().get().get().getCount().toInt().coerceAtLeast(1)

                var rank = 0
                val items: List<LeaderboardItem> = docs.map { d ->
                    rank += 1
                    val pTop = 1.0 - ((rank - 1).toDouble() / totalCount.toDouble())
                    LeaderboardItem(
                        rank = rank,
                        uid = d.id,
                        score = (d.getLong("score") ?: 0L).toInt(),
                        streak = (d.getLong("streak") ?: 0L).toInt(),
                        bestStreak = (d.getLong("bestStreak") ?: 0L).toInt(),
                        league = leagueFromTopPct(pTop)
                    )
                }
                call.respond(items)
            }
        }
    }
}
