// file: src/main/kotlin/com/example/dilemma/routes/UserRoutes.kt
package com.example.dilemma.routes

import com.example.auth.requireLogin
import com.example.dilemma.service.DilemmaService
import com.example.dilemma.service.dto.StandingNear
import com.example.dilemma.service.dto.StandingProgress
import com.example.dilemma.service.dto.StandingResponse
import com.google.cloud.firestore.Query
import com.google.firebase.cloud.FirestoreClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.math.max

fun Route.userRoutes(service: DilemmaService) {

    // ---- küçük yardımcı: top-yüzdeliğe göre lig ----
    fun leagueFromTopPct(pTop: Double): String {
        val p = pTop.coerceIn(0.0, 1.0)
        return when {
            p >= 0.95 -> "elite"
            p >= 0.75 -> "gold"
            p >= 0.40 -> "silver"
            else      -> "bronze"
        }
    }

    route("/me") {

        /* ---- History (binary + multi tek şema) ---- */
        get("/votes") {
            val uid = call.requireLogin() ?: return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 90

            val db = FirestoreClient.getFirestore()
            val docs = db.collectionGroup("votes")
                .whereEqualTo("uid", uid)
                .orderBy("ts", Query.Direction.DESCENDING)
                .limit(limit)
                .get().get().documents

            val items = docs.mapNotNull { vSnap ->
                val dilemmaRef = vSnap.reference.parent.parent ?: return@mapNotNull null
                val dSnap = dilemmaRef.get().get()
                val dilemmaId = dilemmaRef.id

                val title = dSnap.getString("title") ?: ""
                val type = (dSnap.getString("type") ?: "binary").lowercase()
                val isMulti = type == "multi" || (((dSnap.get("options") as? List<*>)?.size ?: 0) >= 3)

                val ts = (vSnap.getLong("ts") ?: 0L)
                val confidence: Double? =
                    vSnap.getDouble("confidence") ?: (vSnap.getLong("confidence")?.toDouble())
                val reason = vSnap.getString("reason")

                val resolved = (vSnap.getBoolean("resolved") == true) || (dSnap.getBoolean("resolved") == true)
                val correct: Boolean? = if (resolved) (vSnap.getBoolean("correct") == true) else null
                val points = ((vSnap.getLong("points") ?: 0L).toInt())
                val variant = vSnap.getString("variant")

                val choiceX = (vSnap.getBoolean("choiceX") == true)
                val optionId = vSnap.getString("optionId")

                // Binary dağılım yüzdeleri (0..1)
                val xCount = (dSnap.getLong("xCount") ?: 0L).toDouble()
                val yCount = (dSnap.getLong("yCount") ?: 0L).toDouble()
                val total = xCount + yCount
                val xPct = if (!isMulti && total > 0.0) (xCount / total) else 0.0
                val yPct = if (!isMulti && total > 0.0) (yCount / total) else 0.0

                // Multi için seçilen opsiyon yüzdesi (0..1), difficultyMulti.q varsa
                @Suppress("UNCHECKED_CAST")
                val diffM = dSnap.get("difficultyMulti") as? Map<*, *>
                @Suppress("UNCHECKED_CAST")
                val qMap  = diffM?.get("q") as? Map<*, *>
                val optionPct01: Double? =
                    if (isMulti && optionId != null && qMap != null)
                        (qMap[optionId] as? Number)?.toDouble()
                    else null

                buildMap<String, Any?> {
                    put("id", dilemmaId)
                    put("dilemmaId", dilemmaId)
                    put("title", title)
                    put("type", if (isMulti) "multi" else "binary")
                    put("choiceX", if (isMulti) false else choiceX)
                    put("optionId", if (isMulti) optionId else null)
                    if (!isMulti) { put("xPct", xPct); put("yPct", yPct) }
                    if (isMulti)  { put("optionPct", optionPct01) }

                    put("ts", ts)
                    put("confidence", confidence)
                    put("reason", reason)
                    put("resolved", resolved)
                    put("correct", correct)
                    put("points", points)
                    put("variant", variant)
                }
            }

            call.respond(items)
        }

        /* ---- Score / streaks + dönemsel puanlar ---- */
        get("/score") {
            val uid = call.requireLogin() ?: return@get
            val db = FirestoreClient.getFirestore()

            val u = db.collection("users").document(uid).get().get()
            val totalScore = (u.getLong("score") ?: 0L).toInt()
            val streak = (u.getLong("streak") ?: 0L).toInt()
            val bestStreak = (u.getLong("bestStreak") ?: 0L).toInt()

            val now = Instant.now()
            val weekStart = now.minus(Duration.ofDays(7)).toEpochMilli()
            val dayStart = now.minus(Duration.ofDays(1)).toEpochMilli()

            fun sumPointsSince(startMs: Long): Int {
                val vs = db.collectionGroup("votes")
                    .whereEqualTo("uid", uid)
                    .whereEqualTo("resolved", true)
                    .whereGreaterThanOrEqualTo("ts", startMs)
                    .whereLessThan("ts", now.toEpochMilli())
                    .orderBy("ts", Query.Direction.DESCENDING)
                    .get().get().documents
                var sum = 0
                for (v in vs) {
                    val p = (v.getLong("points") ?: 0L).toInt()
                    if (p > 0) sum += p
                }
                return sum
            }

            val weekPoints = sumPointsSince(weekStart)
            val dayPoints  = sumPointsSince(dayStart)

            call.respond(mapOf(
                "score" to totalScore,
                "streak" to streak,
                "bestStreak" to bestStreak,
                "weekPoints" to weekPoints,
                "dayPoints" to dayPoints
            ))
        }

        /* ================= Progression (M3.2) ================= */

        @Serializable
        data class ProgressPointDto(
            val ts: Long,
            val score: Int,
            val rankAll: Int? = null,
            val rankWeek: Int? = null,
            val leagueAll: String? = null,
            val leagueWeek: String? = null
        )

        @Serializable
        data class ProgressionResponse(
            val range: String,              // "all" | "week"
            val points: List<ProgressPointDto>
        )

        // Zaman içinde skor / rank değişimi (snapshot’lardan okunur)
        get("/progression") {
            val uid = call.requireLogin() ?: return@get
            val rawRange = (call.request.queryParameters["range"] ?: "all").lowercase()
            val range = if (rawRange == "week") "week" else "all"

            val db = FirestoreClient.getFirestore()
            val col = db.collection("user_progress")
                .document(uid)
                .collection("snapshots")

            val nowMs = System.currentTimeMillis()
            var q: com.google.cloud.firestore.Query = col.orderBy("ts", Query.Direction.ASCENDING)

            // range=week → son 7 gün
            if (range == "week") {
                val weekAgo = nowMs - Duration.ofDays(7).toMillis()
                q = q.whereGreaterThanOrEqualTo("ts", weekAgo)
            }

            // Makul bir limit (ör: 360 noktaya kadar)
            q = q.limit(360)

            val snaps = q.get().get().documents

            val points = snaps.mapNotNull { s ->
                val ts = s.getLong("ts") ?: return@mapNotNull null

                val scoreAll  = (s.getLong("score") ?: s.getLong("scoreAll") ?: 0L).toInt()
                val scoreWeek = (s.getLong("scoreWeek") ?: s.getLong("weekScore") ?: scoreAll).toInt()

                val rankAll   = s.getLong("rankAll")?.toInt()
                val rankWeek  = s.getLong("rankWeek")?.toInt()
                val leagueAll = s.getString("leagueAll")
                val leagueWeek = s.getString("leagueWeek")

                val score = if (range == "week") scoreWeek else scoreAll

                ProgressPointDto(
                    ts = ts,
                    score = score,
                    rankAll = rankAll,
                    rankWeek = rankWeek,
                    leagueAll = leagueAll,
                    leagueWeek = leagueWeek
                )
            }

            call.respond(
                ProgressionResponse(
                    range = range,
                    points = points
                )
            )
        }

        /* ---- Summary ---- */
        get("/summary") {
            val uid = call.requireLogin() ?: return@get
            val dto = service.getUserSummary(uid)
            call.respond(dto)
        }

        /* ---- Category summary (kategori bazlı performans) ---- */
        get("/category-summary") {
            val uid = call.requireLogin() ?: return@get
            val dto = service.getUserCategorySummary(uid)
            call.respond(dto)
        }

        /* ================= Subscription (Kahin Plus iskeleti) ================= */

        @Serializable
        data class SubscriptionDto(
            val tier: String,         // "free", "plus", "pro"
            val since: Long? = null,  // ms since epoch
            val source: String? = null // "mock", "playstore", "stripe"...
        )

        @Serializable
        data class SubscriptionMockBody(
            val tier: String        // "free" | "plus" | "pro"
        )

        // Mevcut abonelik durumunu oku
        get("/subscription") {
            val uid = call.requireLogin() ?: return@get
            val db = FirestoreClient.getFirestore()
            val u = db.collection("users").document(uid).get().get()

            val tier = (u.getString("subscriptionTier") ?: "free").lowercase()
            val since = u.getLong("subscriptionSince")
            val source = u.getString("subscriptionSource")

            call.respond(
                SubscriptionDto(
                    tier = tier,
                    since = since,
                    source = source
                )
            )
        }

        // MVP / geliştirme aşaması için: abonelik tier'ını manuel set et
        // Örn: { "tier": "plus" } veya { "tier": "free" }
        post("/subscription/mock") {
            val uid = call.requireLogin() ?: return@post
            val body = call.receive<SubscriptionMockBody>()

            val raw = body.tier.trim().lowercase()
            val allowed = setOf("free", "plus", "pro")
            if (raw !in allowed) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "invalid tier (use one of: free, plus, pro)"
                )
            }

            val db = FirestoreClient.getFirestore()
            val now = System.currentTimeMillis()

            db.collection("users").document(uid)
                .set(
                    mapOf(
                        "subscriptionTier" to raw,
                        "subscriptionSource" to "mock",
                        "subscriptionSince" to now
                    ),
                    com.google.cloud.firestore.SetOptions.merge()
                )
                .get()

            call.respond(HttpStatusCode.NoContent)
        }

        /* ================= Notifications ================= */

        @Serializable
        data class PrefsBody(
            val notificationsEnabled: Boolean? = null,
            val quietStart: Int? = null, // minutes 0..1439
            val quietEnd: Int? = null    // minutes 0..1439
        )

        get("/prefs") {
            val uid = call.requireLogin() ?: return@get
            val db = FirestoreClient.getFirestore()
            val u = db.collection("users").document(uid).get().get()
            call.respond(
                mapOf(
                    "notificationsEnabled" to (u.getBoolean("notificationsEnabled") != false),
                    "quietStart" to (u.getLong("quietStart")?.toInt()),
                    "quietEnd" to (u.getLong("quietEnd")?.toInt())
                )
            )
        }

        post("/prefs") {
            val uid = call.requireLogin() ?: return@post
            val b = call.receive<PrefsBody>()

            fun clamp(v: Int?) = v?.coerceIn(0, 1439)

            val patch = buildMap<String, Any> {
                b.notificationsEnabled?.let { put("notificationsEnabled", it) }
                clamp(b.quietStart)?.let { put("quietStart", it) }
                clamp(b.quietEnd)?.let { put("quietEnd", it) }
            }
            if (patch.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, "empty")

            val db = FirestoreClient.getFirestore()
            db.collection("users").document(uid)
                .set(patch, com.google.cloud.firestore.SetOptions.merge())
                .get()

            call.respond(HttpStatusCode.NoContent)
        }

        /* ---- FCM token register ---- */
        @Serializable
        data class RegisterFcmBody(
            val token: String,
            val platform: String? = null,
            val tzOffsetMinutes: Int? = null
        )

        post("/fcm/register") {
            val uid = call.requireLogin() ?: return@post
            val b = call.receive<RegisterFcmBody>()
            if (b.token.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, "missing token")

            val db = FirestoreClient.getFirestore()
            val userRef = db.collection("users").document(uid)
            val tokensCol = userRef.collection("fcmTokens")

            val nowMs = System.currentTimeMillis()
            val data = mapOf(
                "token" to b.token,
                "platform" to (b.platform ?: "unknown"),
                "tzOffsetMinutes" to (b.tzOffsetMinutes ?: 0),
                "enabled" to true,
                "updatedAt" to nowMs,
                "createdAt" to nowMs
            )
            tokensCol.document(b.token).set(data, com.google.cloud.firestore.SetOptions.merge()).get()
            userRef.set(mapOf("notificationsEnabled" to true), com.google.cloud.firestore.SetOptions.merge()).get()

            call.respond(HttpStatusCode.NoContent)
        }

        /* ================= Profile ================= */

        @Serializable
        data class ProfilePatch(
            val displayName: String? = null,
            val avatarUrl: String? = null,
            val bio: String? = null,
            val expertiseTags: List<String>? = null,
            val languages: List<String>? = null
        )

        get("/profile") {
            val uid = call.requireLogin() ?: return@get
            val db = FirestoreClient.getFirestore()
            val u = db.collection("users").document(uid).get().get()

            // Yeni alanlar için güvenli okuma + default
            @Suppress("UNCHECKED_CAST")
            val rawExpertise = u.get("expertiseTags") as? List<*>
            val expertiseTags: List<String> =
                rawExpertise
                    ?.mapNotNull { it as? String }
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            val rawLanguages = u.get("languages") as? List<*>
            val languages: List<String> =
                rawLanguages
                    ?.mapNotNull { it as? String }
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    ?: listOf("tr") // default: TR kullanıcısı

            val subscriptionTier = (u.getString("subscriptionTier") ?: "free")
            val role = (u.getString("role") ?: "user")
            val bio = u.getString("bio")

            call.respond(
                mapOf(
                    "displayName" to u.getString("displayName"),
                    "avatarUrl"   to u.getString("avatarUrl"),
                    "bio"         to bio,
                    "expertiseTags" to expertiseTags,
                    "languages"     to languages,
                    "subscriptionTier" to subscriptionTier,
                    "role"            to role
                )
            )
        }

        put("/profile") {
            val uid = call.requireLogin() ?: return@put
            val p = call.receive<ProfilePatch>()

            fun sanitizeTags(list: List<String>?): List<String>? {
                if (list == null) return null
                val cleaned = list
                    .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                    .map { it.lowercase() }
                    .distinct()
                    .take(10)
                return if (cleaned.isEmpty()) emptyList() else cleaned
            }

            fun sanitizeLanguages(list: List<String>?): List<String>? {
                if (list == null) return null
                val cleaned = list
                    .mapNotNull { it.trim().lowercase().takeIf { s -> s.isNotEmpty() } }
                    .distinct()
                    .take(5)
                return if (cleaned.isEmpty()) null else cleaned
            }

            val patch = buildMap<String, Any> {
                p.displayName
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("displayName", it.take(60)) }

                p.avatarUrl
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("avatarUrl", it.take(400)) }

                p.bio
                    ?.trim()
                    ?.let { bio ->
                        if (bio.isEmpty()) {
                            // boş string gönderirse bio'yu silebilmek için
                            put("bio", "")
                        } else {
                            put("bio", bio.take(280))
                        }
                    }

                sanitizeTags(p.expertiseTags)?.let { tags ->
                    // Boş listeyi de yazıyoruz ki "sıfırlama" mümkün olsun
                    put("expertiseTags", tags)
                }

                sanitizeLanguages(p.languages)?.let { langs ->
                    put("languages", langs)
                }
            }

            if (patch.isEmpty()) return@put call.respond(HttpStatusCode.BadRequest, "empty")

            val db = FirestoreClient.getFirestore()
            db.collection("users").document(uid)
                .set(patch, com.google.cloud.firestore.SetOptions.merge())
                .get()

            call.respond(HttpStatusCode.NoContent)
        }

        /* ---- Standing: day/week canlı + league, season/all alanlarından + league ---- */
        get("/standing") {
            val uid = call.requireLogin() ?: return@get
            val db = FirestoreClient.getFirestore()

            val rawRange = (call.request.queryParameters["range"] ?: "week").lowercase()
            val now = Instant.now()

            fun buildStandingFromTotals(totals: Map<String, Int>, label: String): StandingResponse {
                val myScore = totals[uid] ?: 0
                val ranked = totals.entries.sortedByDescending { it.value }
                val n = max(1, ranked.size)
                val myIndex = ranked.indexOfFirst { it.key == uid }.let { if (it >= 0) it else ranked.size }
                val myRank = myIndex + 1

                val pTop = 1.0 - (myIndex.toDouble() / n.toDouble())
                val league = leagueFromTopPct(pTop)

                val from = (myIndex - 5).coerceAtLeast(0)
                val to = (myIndex + 5).coerceAtMost(ranked.size - 1)
                val near: List<StandingNear> =
                    if (ranked.isNotEmpty())
                        ranked.subList(from, to + 1)
                            .filter { it.key != uid }
                            .map { StandingNear(uid = it.key, score = it.value) }
                    else emptyList()

                val progressToNext: StandingProgress? = if (myIndex > 0) {
                    val target = ranked[myIndex - 1]
                    val needed = (target.value - myScore + 1).coerceAtLeast(1)
                    StandingProgress(pointsNeeded = needed, targetUid = target.key, targetScore = target.value)
                } else null

                return StandingResponse(
                    range = label,
                    rank = myRank,
                    score = myScore,
                    league = league,
                    near = near,
                    progressToNext = progressToNext
                )
            }

            when (rawRange) {
                "day", "week" -> {
                    val start = if (rawRange == "day")
                        now.minus(Duration.ofDays(1)).toEpochMilli()
                    else
                        now.minus(Duration.ofDays(7)).toEpochMilli()

                    val votes = db.collectionGroup("votes")
                        .whereEqualTo("resolved", true)
                        .whereGreaterThanOrEqualTo("ts", start)
                        .whereLessThan("ts", now.toEpochMilli())
                        .orderBy("ts", Query.Direction.DESCENDING)
                        .get().get().documents

                    val totals = mutableMapOf<String, Int>()
                    votes.forEach { v ->
                        val uId = v.getString("uid") ?: return@forEach
                        val pts = (v.getLong("points") ?: 0L).toInt()
                        if (pts > 0) totals[uId] = (totals[uId] ?: 0) + pts
                    }
                    if (!totals.containsKey(uid)) totals[uid] = 0

                    call.respond(buildStandingFromTotals(totals, rawRange))
                }

                "season" -> {
                    val users = db.collection("users")
                    val meDoc = users.document(uid).get().get()
                    val myScore = (meDoc.getLong("seasonPoints") ?: 0L).toInt()

                    val higherCount = users.whereGreaterThan("seasonPoints", myScore)
                        .count().get().get().getCount().toDouble()

                    val totalCount = users.count().get().get().getCount().toDouble().coerceAtLeast(1.0)

                    val myRank = (higherCount + 1).toInt()
                    val pTop = 1.0 - (higherCount / totalCount)
                    val league = leagueFromTopPct(pTop)

                    val aboveDocs = users.whereGreaterThan("seasonPoints", myScore)
                        .orderBy("seasonPoints", Query.Direction.ASCENDING).limit(3).get().get().documents
                    val belowDocs = users.whereLessThan("seasonPoints", myScore)
                        .orderBy("seasonPoints", Query.Direction.DESCENDING).limit(3).get().get().documents

                    fun sc(d: com.google.cloud.firestore.DocumentSnapshot) =
                        (d.getLong("seasonPoints") ?: 0L).toInt()

                    val near = buildList {
                        aboveDocs.forEach { add(StandingNear(uid = it.id, score = sc(it))) }
                        belowDocs.forEach { add(StandingNear(uid = it.id, score = sc(it))) }
                    }
                    val nextUp = aboveDocs.firstOrNull()
                    val progressToNext = nextUp?.let {
                        val targetScore = sc(it)
                        val needed = (targetScore - myScore + 1).coerceAtLeast(1)
                        StandingProgress(pointsNeeded = needed, targetUid = it.id, targetScore = targetScore)
                    }

                    call.respond(
                        StandingResponse(
                            range = "season",
                            rank = myRank,
                            score = myScore,
                            league = league,
                            near = near,
                            progressToNext = progressToNext
                        )
                    )
                }

                else -> { // all
                    val users = db.collection("users")
                    val meDoc = users.document(uid).get().get()
                    val myScore = (meDoc.getLong("score") ?: 0L).toInt()

                    val higherCount = users.whereGreaterThan("score", myScore)
                        .count().get().get().getCount().toDouble()

                    val totalCount = users.count().get().get().getCount().toDouble().coerceAtLeast(1.0)

                    val myRank = (higherCount + 1).toInt()
                    val pTop = 1.0 - (higherCount / totalCount)
                    val league = leagueFromTopPct(pTop)

                    val aboveDocs = users.whereGreaterThan("score", myScore)
                        .orderBy("score", Query.Direction.ASCENDING).limit(3).get().get().documents
                    val belowDocs = users.whereLessThan("score", myScore)
                        .orderBy("score", Query.Direction.DESCENDING).limit(3).get().get().documents

                    fun sc(d: com.google.cloud.firestore.DocumentSnapshot) =
                        (d.getLong("score") ?: 0L).toInt()

                    val near = buildList {
                        aboveDocs.forEach { add(StandingNear(uid = it.id, score = sc(it))) }
                        belowDocs.forEach { add(StandingNear(uid = it.id, score = sc(it))) }
                    }
                    val nextUp = aboveDocs.firstOrNull()
                    val progressToNext = nextUp?.let {
                        val targetScore = sc(it)
                        val needed = (targetScore - myScore + 1).coerceAtLeast(1)
                        StandingProgress(pointsNeeded = needed, targetUid = it.id, targetScore = targetScore)
                    }

                    call.respond(
                        StandingResponse(
                            range = "all",
                            rank = myRank,
                            score = myScore,
                            league = league,
                            near = near,
                            progressToNext = progressToNext
                        )
                    )
                }
            }
        }
    }
}
