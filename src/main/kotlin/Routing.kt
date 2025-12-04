package com.example

import com.example.auth.FirebaseAuthPlugin
import com.example.dilemma.repository.FirestoreDilemmaRepo
import com.example.dilemma.routes.adminRoutes
import com.example.dilemma.routes.dilemmaRoutes
import com.example.dilemma.routes.userRoutes
import com.example.dilemma.service.DilemmaService
import com.example.leaderboard.routes.leaderboardRoutes
import com.example.notifications.NotificationService
import com.example.submission.repository.FirestoreSubmissionRepo
import com.example.submission.routes.submissionRoutes
import com.example.submission.service.SubmissionService
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Query
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Date

@Serializable
data class AddTestResponse(val id: String, val ts: Long)

fun Application.configureRouting() {

    // 🔎 Emülatör durumu (log)
    val fsEmu  = System.getenv("FIRESTORE_EMULATOR_HOST")
    val auEmu  = System.getenv("FIREBASE_AUTH_EMULATOR_HOST")
    if (fsEmu != null || auEmu != null) {
        environment.log.info("Firebase EMULATORS enabled → firestore=$fsEmu auth=$auEmu")
    } else {
        environment.log.info("Firebase EMULATORS disabled")
    }

    /* ---------- Firebase init (once) ---------- */
    if (FirebaseApp.getApps().isEmpty()) {
        val projectId = System.getenv("GOOGLE_CLOUD_PROJECT")
            ?: System.getProperty("GOOGLE_CLOUD_PROJECT")
            ?: "gunun-ikilemi"

        val usingEmulators = (fsEmu != null || auEmu != null)

        val builder = FirebaseOptions.builder()
            .setProjectId(projectId)

        if (usingEmulators) {
            // ✅ Admin SDK null credential kabul etmez; emülatörde sahte token veriyoruz.
            val fakeCreds = GoogleCredentials.create(
                AccessToken("owner", Date(Long.MAX_VALUE))
            )
            builder.setCredentials(fakeCreds)
        } else {
            val keyPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")
                ?: System.getProperty("GOOGLE_APPLICATION_CREDENTIALS")

            val creds = keyPath?.let { p ->
                File(p).inputStream().use { GoogleCredentials.fromStream(it) }
            } ?: GoogleCredentials.getApplicationDefault()

            builder.setCredentials(creds)
        }

        FirebaseApp.initializeApp(builder.build())
    }

    val db = FirestoreClient.getFirestore()

    /* ---------- Auth plugin (optional token) ---------- */
    install(FirebaseAuthPlugin) { optional = true }

    /* ---------- Routes ---------- */
    routing {
        // Prometheus metrics
        get("/metrics") {
            val reg = this@configureRouting.attributes[PromRegistryKey]
            call.respondText(
                reg.scrape(),
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
            )
        }

        // Core services
        val dilemmaRepo    = FirestoreDilemmaRepo(db)
        val dilemmaService = DilemmaService(dilemmaRepo)

        // Submissions (M5)
        val submissionRepo    = FirestoreSubmissionRepo() // parametresiz
        val submissionService = SubmissionService(submissionRepo, dilemmaService)

        // Feature routes
        dilemmaRoutes(dilemmaService)        // /dilemma/…
        adminRoutes(dilemmaService)          // /admin/…
        userRoutes(dilemmaService)           // /me/…
        leaderboardRoutes()                  // /leaderboard?range=all|week|day
        submissionRoutes(submissionService)  // /submissions… & /admin/submissions…

        // Healthcheck
        get("/ping") { call.respondText("pong") }

        // Firestore küçük test
        post("/add") {
            val ts  = System.currentTimeMillis()
            val ref = db.collection("tests").add(mapOf("ts" to ts)).get()
            call.respond(HttpStatusCode.Created, AddTestResponse(id = ref.id, ts = ts))
        }

        /* ===== M7: CRON – Daily notify ===== */
        get("/cron/daily-notify") {
            val secret = System.getenv("CRON_TOKEN")
            if (secret != null && call.request.headers["X-CRON-TOKEN"] != secret) {
                call.respond(HttpStatusCode.Unauthorized, "bad token"); return@get
            }

            val windowMin = call.request.queryParameters["window"]?.toIntOrNull() ?: 10

            val nowMillis  = System.currentTimeMillis()
            val utcMinutes = ((nowMillis / 60000L) % (24 * 60)).toInt()

            val snaps = db.collectionGroup("fcmTokens")
                .whereEqualTo("enabled", true)
                .get().get().documents

            var checked = 0
            var sent = 0

            for (tokenDoc in snaps) {
                checked += 1
                val token = tokenDoc.getString("token") ?: continue
                val tzOff = (tokenDoc.getLong("tzOffsetMinutes") ?: 0L).toInt()

                val localMinutes = (utcMinutes + tzOff + 1440) % 1440
                val localHour = localMinutes / 60
                val localMin  = localMinutes % 60
                if (!(localHour == 9 && localMin in 0 until windowMin)) continue

                val userRef  = tokenDoc.reference.parent.parent ?: continue
                val uid = userRef.id
                val userSnap = userRef.get().get()

                val enabled = userSnap.getBoolean("notificationsEnabled") != false
                if (!enabled) continue

                val qs = (userSnap.getLong("quietStart") ?: -1L).toInt()
                val qe = (userSnap.getLong("quietEnd") ?: -1L).toInt()
                if (qs in 0..1439 && qe in 0..1439) {
                    val l = localMinutes
                    val silent = if (qs <= qe) (l in qs..qe) else (l >= qs || l <= qe)
                    if (silent) continue
                }

                val todayUtc = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                val last = userSnap.getString("lastDailyNotifiedDate")
                if (last == todayUtc) continue

                try {
                    NotificationService.sendDaily(uid, token)
                    sent += 1
                    userRef.set(
                        mapOf("lastDailyNotifiedDate" to todayUtc),
                        com.google.cloud.firestore.SetOptions.merge()
                    ).get()
                } catch (_: Exception) {
                    // optional logging
                }
            }

            call.respond(mapOf("checked" to checked, "sent" to sent))
        }

        /* ===== M3.1: CRON – Result notify ===== */
        get("/cron/result-notify") {
            val secret = System.getenv("CRON_TOKEN")
            if (secret != null && call.request.headers["X-CRON-TOKEN"] != secret) {
                call.respond(HttpStatusCode.Unauthorized, "bad token"); return@get
            }

            val windowMin = call.request.queryParameters["window"]?.toIntOrNull() ?: 60
            val windowMs = windowMin.coerceAtLeast(1) * 60_000L

            val nowMs = System.currentTimeMillis()
            val fromMs = nowMs - windowMs

            val dilemmas = db.collection("dilemmas")
                .whereEqualTo("resolved", true)
                .whereGreaterThanOrEqualTo("resolvedAt", fromMs)
                .whereLessThan("resolvedAt", nowMs)
                .get().get().documents

            var votesChecked = 0
            var notified = 0

            dilemmas.forEach { d ->
                val title = d.getString("title") ?: ""
                val votesSnap = d.reference.collection("votes").get().get().documents

                votesSnap.forEach { v ->
                    votesChecked += 1
                    val uid = v.getString("uid") ?: return@forEach
                    val already = v.getLong("resultNotifiedAt")
                    if (already != null && already > 0L) return@forEach

                    val correct = v.getBoolean("correct") == true
                    val points = (v.getLong("points") ?: 0L).toInt()

                    try {
                        NotificationService.sendResult(uid, title, correct, points)
                        notified += 1
                        v.reference.update(
                            mapOf("resultNotifiedAt" to nowMs)
                        ).get()
                    } catch (_: Exception) {
                        // sessizce geç
                    }
                }
            }

            call.respond(
                mapOf(
                    "dilemmas" to dilemmas.size,
                    "votesChecked" to votesChecked,
                    "notified" to notified,
                    "windowMinutes" to windowMin
                )
            )
        }

        /* ===== M3.2: CRON – User progression snapshot ===== */
        get("/cron/user-progress-snapshot") {
            val secret = System.getenv("CRON_TOKEN")
            if (secret != null && call.request.headers["X-CRON-TOKEN"] != secret) {
                call.respond(HttpStatusCode.Unauthorized, "bad token"); return@get
            }

            val usersCol = db.collection("users")
            val minScore = (call.request.queryParameters["minScore"]?.toIntOrNull() ?: 0)
                .coerceAtLeast(0)

            // Aktif kullanıcılar: score > minScore
            val activeSnap = usersCol
                .whereGreaterThan("score", minScore.toLong())
                .get().get()
            val activeDocs = activeSnap.documents

            if (activeDocs.isEmpty()) {
                call.respond(mapOf("activeUsers" to 0, "snapshots" to 0))
                return@get
            }

            // All-time rank (score desc)
            val orderedAll = usersCol
                .orderBy("score", Query.Direction.DESCENDING)
                .get().get().documents

            val rankAll = mutableMapOf<String, Int>()
            var rAll = 0
            for (d in orderedAll) {
                val sc = (d.getLong("score") ?: 0L).toInt()
                if (sc <= minScore) break
                rAll += 1
                rankAll[d.id] = rAll
            }
            val totalAll = rAll.coerceAtLeast(1)

            // Weekly rank (weeklyPoints desc, >0)
            val orderedWeek = usersCol
                .orderBy("weeklyPoints", Query.Direction.DESCENDING)
                .get().get().documents

            val rankWeek = mutableMapOf<String, Int>()
            var rW = 0
            for (d in orderedWeek) {
                val wp = (d.getLong("weeklyPoints") ?: 0L).toInt()
                if (wp <= 0) break
                rW += 1
                rankWeek[d.id] = rW
            }
            val totalWeek = rW.coerceAtLeast(1)

            fun leagueFromTopPct(pTop: Double): String {
                val p = pTop.coerceIn(0.0, 1.0)
                return when {
                    p >= 0.95 -> "elite"
                    p >= 0.75 -> "gold"
                    p >= 0.40 -> "silver"
                    else      -> "bronze"
                }
            }

            val nowMs = System.currentTimeMillis()
            var written = 0
            val batchSize = 400
            var batch = db.batch()
            var ops = 0

            activeDocs.forEach { u ->
                val uid = u.id
                val score = (u.getLong("score") ?: 0L).toInt()
                val weekPts = (u.getLong("weeklyPoints") ?: 0L).toInt()

                val ra = rankAll[uid]
                val rw = rankWeek[uid]

                val leagueAll = ra?.let {
                    val pTop = 1.0 - ((it - 1).toDouble() / totalAll.toDouble())
                    leagueFromTopPct(pTop)
                }
                val leagueWeek = rw?.let {
                    val pTop = 1.0 - ((it - 1).toDouble() / totalWeek.toDouble())
                    leagueFromTopPct(pTop)
                }

                val data = mutableMapOf<String, Any>(
                    "ts" to nowMs,
                    "score" to score,
                    "weeklyPoints" to weekPts
                )
                ra?.let { data["rankAll"] = it }
                rw?.let { data["rankWeek"] = it }
                leagueAll?.let { data["leagueAll"] = it }
                leagueWeek?.let { data["leagueWeek"] = it }

                val docRef = db.collection("user_progress").document(uid)
                    .collection("snapshots").document(nowMs.toString())

                batch.set(docRef, data)
                ops++
                written++

                if (ops >= batchSize) {
                    batch.commit().get()
                    batch = db.batch()
                    ops = 0
                }
            }

            if (ops > 0) {
                batch.commit().get()
            }

            call.respond(
                mapOf(
                    "activeUsers" to activeDocs.size,
                    "snapshots" to written
                )
            )
        }

        /* ===== M11: CRON – Leaderboard cache ===== */
        get("/cron/leaderboard-cache") {
            val secret = System.getenv("CRON_TOKEN")
            if (secret != null && call.request.headers["X-CRON-TOKEN"] != secret) {
                call.respond(HttpStatusCode.Unauthorized, "bad token"); return@get
            }

            val rangeParam = call.request.queryParameters["range"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "range=day|week required")

            val range = rangeParam
            if (range != "day" && range != "week") {
                call.respond(HttpStatusCode.BadRequest, "range must be 'day' or 'week'")
                return@get
            }
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)

            val now   = Instant.now()
            val start = if (range == "week") now.minus(Duration.ofDays(7)) else now.minus(Duration.ofDays(1))

            val votes = db.collectionGroup("votes")
                .whereEqualTo("resolved", true)
                .whereGreaterThanOrEqualTo("ts", start.toEpochMilli())
                .whereLessThan("ts", now.toEpochMilli())
                .orderBy("ts", Query.Direction.DESCENDING)
                .get().get().documents

            val totals = mutableMapOf<String, Int>()
            votes.forEach { v ->
                val uid = v.getString("uid") ?: return@forEach
                val pts = (v.getLong("points") ?: 0L).toInt()
                if (pts > 0) totals[uid] = (totals[uid] ?: 0) + pts
            }

            val sorted = totals.entries.sortedByDescending { it.value }.take(limit)

            var rank = 0
            val items = sorted.map { (uid, rangeScore) ->
                val u = db.collection("users").document(uid).get().get()
                rank += 1
                mapOf(
                    "rank" to rank,
                    "uid" to uid,
                    "score" to rangeScore, // range’e ait puan
                    "streak" to (u.getLong("streak") ?: 0L).toInt(),
                    "bestStreak" to (u.getLong("bestStreak") ?: 0L).toInt()
                )
            }

            db.collection("leaderboards").document(range)
                .set(
                    mapOf(
                        "range" to range,
                        "updatedAt" to System.currentTimeMillis(),
                        "items" to items
                    ),
                    com.google.cloud.firestore.SetOptions.merge()
                ).get()

            call.respond(mapOf("range" to range, "count" to items.size))
        }
    }
}
