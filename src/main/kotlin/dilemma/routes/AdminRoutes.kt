package com.example.dilemma.routes

import com.example.auth.requireAdmin
import com.example.dilemma.service.DilemmaService
import com.example.dilemma.service.dto.LbItem
import com.example.dilemma.service.dto.LbSnapshot
import com.example.dilemma.service.dto.MultiSetupBody
import com.example.dilemma.service.dto.OptionInput
import com.example.dilemma.service.dto.LeagueCuts
import com.example.dilemma.service.dto.LeagueRecomputeResponse
import com.example.dilemma.service.dto.MultiSetupResponse
import com.google.cloud.firestore.FieldPath
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Query
import com.google.firebase.cloud.FirestoreClient
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import java.time.Duration
import java.time.Instant

@Serializable
private data class NewDilemmaBody(val title: String)

@Serializable
private data class IdResponse(val id: String)

@Serializable
private data class ResolveBody(
    val correctX: Boolean? = null,     // Binary
    val correctId: String? = null,     // Multi
    val points: Int? = 10
)

fun Route.adminRoutes(service: DilemmaService) = route("/admin") {

    /* ========= NEW: Multi kurulum ========= */
    put("/dilemma/{id}/multi") {
        call.requireAdmin() ?: return@put

        val id = call.parameters["id"] ?: return@put call.respond(
            HttpStatusCode.BadRequest, "missing id"
        )
        val body = call.receive<MultiSetupBody>()

        if (body.options.size < 3) {
            return@put call.respond(HttpStatusCode.BadRequest, "min 3 option required")
        }
        val ids = body.options.map { it.id.trim() }
        if (ids.any { it.isEmpty() } || ids.toSet().size != ids.size) {
            return@put call.respond(HttpStatusCode.BadRequest, "option ids must be non-empty & unique")
        }

        val db = FirestoreClient.getFirestore()
        val ref = db.collection("dilemmas").document(id)

        val optionsArray = body.options.map { o ->
            mapOf("id" to o.id.trim(), "label" to o.label.trim())
        }.toList()

        val data = mutableMapOf<String, Any>(
            "type" to "multi",
            "options" to optionsArray
        )
        if (body.withCounts) {
            data["optionCounts"] = ids.associateWith { 0L }
        }

        ref.set(data, com.google.cloud.firestore.SetOptions.merge()).get()

        // DTO ile cevapla (serializer hatalarını önlemek için)
        val optionsDto = body.options.map { OptionInput(it.id.trim(), it.label.trim()) }
        call.respond(HttpStatusCode.OK, MultiSetupResponse(id = id, type = "multi", options = optionsDto))
    }
    /* ========= END: Multi kurulum ========= */

    post("/dilemma") {
        call.requireAdmin() ?: return@post
        val body = call.receive<NewDilemmaBody>()
        val id   = service.createDaily(body.title)
        call.respond(HttpStatusCode.Created, IdResponse(id))
    }

    post("/dilemma/{id}/resolve") {
        call.requireAdmin() ?: return@post

        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val body = call.receive<ResolveBody>()
        val basePoints = (body.points ?: 10).coerceAtLeast(0)

        val db = FirestoreClient.getFirestore()
        val dilemmaRef = db.collection("dilemmas").document(id)

        val dSnap = dilemmaRef.get().get()
        if (!dSnap.exists()) return@post call.respond(HttpStatusCode.NotFound, "Dilemma not found")

        // Tip belirleme
        @Suppress("UNCHECKED_CAST")
        val optsRaw = (dSnap.get("options") as? List<Map<String, Any?>>) ?: emptyList()
        val optIds = optsRaw.mapNotNull { (it["id"] as? String)?.trim() }.toSet()
        val type = dSnap.getString("type") ?: "binary"
        val isMulti = (type == "multi") || optIds.size >= 3

        // --------- yardımcılar ---------
        fun log2(x: Double): Double = ln(x) / ln(2.0)
        fun entropyBitsFromProb(p: Double): Double =
            if (p <= 0.0 || p >= 1.0) 0.0 else (-(p * ln(p) + (1 - p) * ln(1 - p)) / ln(2.0))

        // ---- Binary varyant konfig ----
        data class Vals(val alpha: Double, val beta: Double)
        val cfgSnap = db.collection("config").document("scoring").get().get()
        val defaultVariant = (cfgSnap.getString("default") ?: "A")
        val variants: MutableMap<String, Vals> = mutableMapOf("A" to Vals(alpha = 0.5, beta = 1.0))
        if (cfgSnap.exists()) {
            val vmap = cfgSnap.get("variants") as? Map<*, *>
            vmap?.forEach { (k, v) ->
                val m = v as? Map<*, *>
                val a = (m?.get("alpha") as? Number)?.toDouble() ?: 0.5
                val b = (m?.get("beta")  as? Number)?.toDouble() ?: 1.0
                variants[k.toString()] = Vals(a, b)
            }
        }

        if (!isMulti) {
            // ---------------- BINARY RESOLVE ----------------
            val xCount = (dSnap.getLong("xCount") ?: 0L).toDouble()
            val yCount = (dSnap.getLong("yCount") ?: 0L).toDouble()
            val total  = xCount + yCount
            val pX = if (total > 0) xCount / total else 0.5
            val pY = 1.0 - pX

            val H = entropyBitsFromProb(pX) // bits
            val correctX = body.correctX ?: return@post call.respond(HttpStatusCode.BadRequest, "correctX required")
            val correctShare = if (correctX) pX else pY
            val underdog = max(0.0, 0.5 - correctShare) / 0.5

            val bonusByVariant: Map<String, Double> = variants.mapValues { (_, vv) ->
                1.0 + vv.alpha * H + vv.beta * underdog
            }
            dilemmaRef.update(
                mapOf(
                    "resolved" to true,
                    "correctX" to correctX,
                    "points" to basePoints,
                    "resolvedAt" to System.currentTimeMillis(),
                    "difficultyMeta" to mapOf(
                        "pX" to pX,
                        "pY" to pY,
                        "entropy" to H,
                        "bonusFactors" to bonusByVariant
                    )
                )
            ).get()

            val votes = dilemmaRef.collection("votes").get().get().documents
            val batch = db.batch()
            data class Agg(var total:Int=0, var correct:Int=0, var points:Int=0)
            val perVar = mutableMapOf<String, Agg>()

            votes.forEach { snap ->
                val choiceX = (snap.getBoolean("choiceX") == true)
                val uid = snap.getString("uid") ?: return@forEach
                val correct = (choiceX == correctX)

                val variant = snap.getString("variant") ?: defaultVariant
                val vals = variants[variant] ?: variants[defaultVariant]!!
                val factor = 1.0 + vals.alpha * H + vals.beta * underdog
                val earnedIfCorrect = max(0.0, round(basePoints.toDouble() * factor)).toInt()
                val earned = if (correct) earnedIfCorrect else 0

                batch.update(
                    snap.reference,
                    mapOf("resolved" to true, "correct" to correct, "points" to earned, "variant" to variant)
                )

                // ✅ score + weeklyPoints + seasonPoints + allTimePoints
                val inc = FieldValue.increment(earned.toLong())
                batch.set(
                    db.collection("users").document(uid),
                    mapOf(
                        "score" to inc,
                        "weeklyPoints" to inc,
                        "seasonPoints" to inc,
                        "allTimePoints" to inc
                    ),
                    com.google.cloud.firestore.SetOptions.merge()
                )

                val agg = perVar.getOrPut(variant) { Agg() }
                agg.total += 1; if (correct) agg.correct += 1; agg.points += earned
            }
            batch.commit().get()

            val expDoc = db.collection("experiments").document("scoring")
                .collection("dilemmas").document(id)
            val variantsMeta = variants.mapValues { (_, v) -> mapOf("alpha" to v.alpha, "beta" to v.beta) }
            val perVarOut = perVar.mapValues { (_, a) ->
                mapOf(
                    "n" to a.total,
                    "correct" to a.correct,
                    "accuracy" to if (a.total>0) a.correct.toDouble()/a.total else 0.0,
                    "points" to a.points
                )
            }
            expDoc.set(
                mapOf(
                    "dilemmaId" to id,
                    "resolvedAt" to System.currentTimeMillis(),
                    "pX" to pX, "pY" to pY, "entropy" to H,
                    "underdog" to underdog,
                    "basePoints" to basePoints,
                    "variants" to variantsMeta,
                    "results" to perVarOut,
                    "default" to defaultVariant
                )
            ).get()

            call.respond(HttpStatusCode.OK, mapOf("updated" to votes.size))
            return@post
        }

        // ---------------- MULTI RESOLVE ----------------
        val cfgM = db.collection("config").document("scoring_multi").get().get()
        val alpha0 = cfgM.getDouble("alpha0") ?: 0.5
        val alpha  = cfgM.getDouble("alpha")  ?: 0.4
        val beta   = cfgM.getDouble("beta")   ?: 0.9
        val gamma  = cfgM.getDouble("gamma")  ?: 0.3
        val sMax   = cfgM.getDouble("Smax")   ?: 1.5
        val base0  = (cfgM.getLong("base0") ?: 10L).toInt()
        val penaltyWrong = (cfgM.getLong("penaltyWrong") ?: 0L).toInt()
        val scale  = cfgM.getString("scale") ?: "logK"

        val correctId = body.correctId?.trim()
            ?: return@post call.respond(HttpStatusCode.BadRequest, "correctId required for multi")
        if (correctId !in optIds) {
            return@post call.respond(HttpStatusCode.BadRequest, "unknown correctId")
        }

        @Suppress("UNCHECKED_CAST")
        val diff = dSnap.get("difficultyMulti") as? Map<*, *>
        val counts: Map<String, Long> = (diff?.get("counts") as? Map<*, *>)?.mapNotNull { (k, v) ->
            val key = (k as? String)?.trim(); val vv = (v as? Number)?.toLong()
            if (key != null && vv != null) key to vv else null
        }?.toMap() ?: run {
            val votesCol = dilemmaRef.collection("votes")
            optIds.associateWith { oid ->
                votesCol.whereEqualTo("optionId", oid).get().get().documents.size.toLong()
            }
        }

        val kSize = counts.size.coerceAtLeast(3)
        val N = counts.values.fold(0L) { a, b -> a + b }.toDouble()
        val denom = N + kSize * alpha0
        val q: Map<String, Double> = counts.mapValues { (_, c) -> (c + alpha0) / denom }

        val qVals = q.values.toList().ifEmpty { listOf(1.0 / kSize) }
        val Hbits = qVals.sumOf { qi -> if (qi <= 0.0) 0.0 else -qi * log2(qi) }
        val Hn = if (kSize > 1) (Hbits / log2(kSize.toDouble())) else 0.0
        val topSorted = qVals.sortedDescending()
        val gap = if (topSorted.size >= 2) (topSorted[0] - topSorted[1]) else topSorted.getOrElse(0) { 1.0 }
        val Mn = 1.0 - (gap / (1.0 - 1.0 / kSize))
        val qCorrect = q[correctId] ?: (1.0 / kSize)
        val Sn = min((-ln(qCorrect) / ln(kSize.toDouble())), sMax)

        val BK: Double = when (scale.lowercase()) {
            "logk" -> base0 * log2(kSize.toDouble())
            else   -> base0 * log2(kSize.toDouble())
        }
        val factor = 1.0 + alpha * Hn + beta * Sn + gamma * Mn
        val earnedIfCorrect = max(0.0, round(BK * factor)).toInt()

        dilemmaRef.update(
            mapOf(
                "resolved" to true,
                "correctId" to correctId,
                "points" to base0,
                "resolvedAt" to System.currentTimeMillis(),
                "difficultyMulti" to mapOf(
                    "K" to kSize,
                    "counts" to counts,
                    "q" to q,
                    "entropyBits" to Hbits,
                    "entropyNorm" to Hn,
                    "marginNorm" to Mn,
                    "surprisalNorm" to Sn,
                    "alpha0" to alpha0,
                    "alpha" to alpha,
                    "beta" to beta,
                    "gamma" to gamma,
                    "Smax" to sMax,
                    "baseScale" to scale,
                    "BK" to BK
                )
            )
        ).get()

        val votes = dilemmaRef.collection("votes").get().get().documents
        val batch = db.batch()
        var updated = 0
        votes.forEach { snap ->
            val uid = snap.getString("uid") ?: return@forEach
            val oid = snap.getString("optionId") ?: return@forEach
            val correct = (oid == correctId)
            val earned = if (correct) earnedIfCorrect else penaltyWrong

            batch.update(
                snap.reference,
                mapOf("resolved" to true, "correct" to correct, "points" to earned)
            )

            // ✅ score + weeklyPoints + seasonPoints + allTimePoints
            val inc = FieldValue.increment(earned.toLong())
            batch.set(
                db.collection("users").document(uid),
                mapOf(
                    "score" to inc,
                    "weeklyPoints" to inc,
                    "seasonPoints" to inc,
                    "allTimePoints" to inc
                ),
                com.google.cloud.firestore.SetOptions.merge()
            )
            updated += 1
        }
        batch.commit().get()

        db.collection("experiments").document("scoring_multi")
            .collection("dilemmas").document(id)
            .set(
                mapOf(
                    "dilemmaId" to id,
                    "resolvedAt" to System.currentTimeMillis(),
                    "K" to kSize,
                    "counts" to counts,
                    "q" to q,
                    "Hn" to Hn,
                    "Mn" to Mn,
                    "Sn" to Sn,
                    "BK" to BK,
                    "earnedIfCorrect" to earnedIfCorrect,
                    "penaltyWrong" to penaltyWrong,
                    "correctId" to correctId
                )
            ).get()

        call.respond(HttpStatusCode.OK, mapOf("updated" to votes.size))
    }

    /* ========= LİG ATAMA (batch) ========= */
    post("/league/recompute") {
        call.requireAdmin() ?: return@post
        val db = FirestoreClient.getFirestore()
        val users = db.collection("users")

        val fieldParam = (call.request.queryParameters["field"] ?: "weekly").lowercase()
        val dryRun = call.request.queryParameters["dryRun"]?.toBooleanStrictOrNull() ?: false

        val field = when (fieldParam) {
            "week", "weekly", "weeklypoints" -> "weeklyPoints"
            "season", "seasonpoints"         -> "seasonPoints"
            "all", "alltime", "alltimepoints"-> "allTimePoints"
            "score"                           -> "score"
            else                              -> "weeklyPoints"
        }

        val total = users.count().get().get().getCount().toInt()
        if (total == 0) {
            val resp = LeagueRecomputeResponse(
                total = 0,
                processed = 0,
                writes = 0,
                field = field,
                cuts = LeagueCuts(eliteCut = 0, goldCut = 0, silverCut = 0),
                dryRun = dryRun
            )
            return@post call.respond(resp)
        }

        val eliteCut  = max(1, ceil(total * 0.05).toInt())
        val goldCut   = max(eliteCut, ceil(total * 0.25).toInt())
        val silverCut = max(goldCut,  ceil(total * 0.60).toInt())

        fun leagueForRank(rank: Int): String = when {
            rank <= eliteCut  -> "elite"
            rank <= goldCut   -> "gold"
            rank <= silverCut -> "silver"
            else              -> "bronze"
        }

        val now = System.currentTimeMillis()
        val pageSize = 500
        var rank = 0
        var processed = 0
        var writes = 0

        var lastScoreAny: Any? = null
        var lastId: String? = null

        while (true) {
            var q: com.google.cloud.firestore.Query = users
                .orderBy(field, Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                .limit(pageSize)

            if (lastId != null) {
                q = q.startAfter(lastScoreAny, lastId)
            }

            val page = q.get().get().documents
            if (page.isEmpty()) break

            val batch = db.batch()
            page.forEach { d ->
                rank += 1
                processed += 1
                val league = leagueForRank(rank)

                if (!dryRun) {
                    batch.set(
                        d.reference,
                        mapOf("league" to league, "leagueUpdatedAt" to now),
                        com.google.cloud.firestore.SetOptions.merge()
                    )
                    writes += 1
                }
            }

            if (!dryRun) batch.commit().get()

            val tail = page.last()
            lastScoreAny = tail.get(field)
            lastId = tail.id
        }

        val resp = LeagueRecomputeResponse(
            total = total,
            processed = processed,
            writes = if (dryRun) 0 else writes,
            field = field,
            cuts = LeagueCuts(eliteCut = eliteCut, goldCut = goldCut, silverCut = silverCut),
            dryRun = dryRun
        )
        call.respond(resp)
    }
    /* ========= END: LİG ATAMA ========= */

    /* ========= SNAPSHOT WRITER (day|week) =========
       POST /admin/leaderboard/snapshot?range=day|week&limit=200
       items -> [{rank, uid, score, streak, bestStreak}], updatedAt
    */
    post("/leaderboard/snapshot") {
        call.requireAdmin() ?: return@post

        val range = (call.request.queryParameters["range"] ?: "day").lowercase()
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)

        if (range != "day" && range != "week") {
            return@post call.respond(HttpStatusCode.BadRequest, "range must be day|week")
        }

        val db = FirestoreClient.getFirestore()
        val now = Instant.now()
        val start = if (range == "day") now.minus(Duration.ofDays(1)) else now.minus(Duration.ofDays(7))

        val votes = db.collectionGroup("votes")
            .whereEqualTo("resolved", true)
            .whereGreaterThanOrEqualTo("ts", start.toEpochMilli())
            .whereLessThan("ts", now.toEpochMilli())
            .orderBy("ts", Query.Direction.DESCENDING)
            .get().get().documents

        val totals = mutableMapOf<String, Int>() // uid -> period score
        votes.forEach { v ->
            val uid = v.getString("uid") ?: return@forEach
            val pts = (v.getLong("points") ?: 0L).toInt()
            if (pts > 0) totals[uid] = (totals[uid] ?: 0) + pts
        }

        val sorted = totals.entries.sortedByDescending { it.value }.take(limit)

        var rank = 0
        val items: List<LbItem> = sorted.map { (uid, score) ->
            val u = db.collection("users").document(uid).get().get()
            rank += 1
            LbItem(
                rank = rank,
                uid = uid,
                score = score,
                streak = (u.getLong("streak") ?: 0L).toInt(),
                bestStreak = (u.getLong("bestStreak") ?: 0L).toInt()
            )
        }

        val out = LbSnapshot(
            items = items,
            updatedAt = System.currentTimeMillis(),
            range = range,
            count = items.size
        )

        // Firestore'a yazarken map'e dön (POJO yerine güvenli yol)
        val docMap = mapOf(
            "items" to items.map {
                mapOf(
                    "rank" to it.rank,
                    "uid" to it.uid,
                    "score" to it.score,
                    "streak" to it.streak,
                    "bestStreak" to it.bestStreak
                )
            },
            "updatedAt" to out.updatedAt,
            "range" to out.range,
            "count" to out.count
        )

        db.collection("leaderboards").document(range)
            .set(docMap, com.google.cloud.firestore.SetOptions.merge())
            .get()

        call.respond(out)
    }
    /* ========= END: SNAPSHOT WRITER ========= */

    /* ========= SEASON RESET (opsiyonel) =========
       POST /admin/season/reset?seasonId=YYYY-MM
       Tüm kullanıcılar için seasonPoints=0, seasonId=param, seasonStartedAt=now
    */
    post("/season/reset") {
        call.requireAdmin() ?: return@post
        val seasonId = call.request.queryParameters["seasonId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, "missing seasonId"
        )

        val db = FirestoreClient.getFirestore()
        val users = db.collection("users")
        val now = System.currentTimeMillis()
        val pageSize = 500
        var processed = 0
        var lastId: String? = null

        while (true) {
            var q: com.google.cloud.firestore.Query = users
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                .limit(pageSize)
            if (lastId != null) q = q.startAfter(lastId)

            val page = q.get().get().documents
            if (page.isEmpty()) break

            val batch = db.batch()
            page.forEach { d ->
                batch.set(
                    d.reference,
                    mapOf(
                        "seasonPoints" to 0L,
                        "seasonId" to seasonId,
                        "seasonStartedAt" to now
                    ),
                    com.google.cloud.firestore.SetOptions.merge()
                )
                processed += 1
            }
            batch.commit().get()
            lastId = page.last().id
        }

        call.respond(mapOf("seasonId" to seasonId, "processed" to processed))
    }

    /* Yönetim listesi */
    get("/dilemmas") {
        call.requireAdmin() ?: return@get
        val status = call.request.queryParameters["status"] ?: "open"
        val limit  = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

        val db  = FirestoreClient.getFirestore()
        val col = db.collection("dilemmas")

        val query = when (status) {
            "resolved" -> col.whereEqualTo("resolved", true)
                .orderBy("resolvedAt", Query.Direction.DESCENDING)
            else -> col.whereEqualTo("resolved", false)
                .orderBy("createdAt", Query.Direction.DESCENDING)
        }

        val docs = query.limit(limit).get().get().documents

        val items = docs.map { d ->
            mapOf(
                "id"         to d.id,
                "title"      to (d.getString("title") ?: ""),
                "xCount"     to ((d.getLong("xCount") ?: 0L).toInt()),
                "yCount"     to ((d.getLong("yCount") ?: 0L).toInt()),
                "resolved"   to (d.getBoolean("resolved") == true),
                "correctX"   to (d.getBoolean("correctX") == true),
                "points"     to ((d.getLong("points") ?: 0L).toInt()),
                "createdAt"  to (d.getLong("createdAt") ?: 0L),
                "resolvedAt" to (d.getLong("resolvedAt") ?: 0L)
            )
        }

        call.respond(items)
    }
}
