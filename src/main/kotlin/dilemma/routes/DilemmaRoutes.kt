// file: src/main/kotlin/com/example/dilemma/routes/DilemmaRoutes.kt
package com.example.dilemma.routes

import com.example.PromRegistryKey
import com.example.auth.requireLogin
import com.example.auth.uidOrNull
import com.example.dilemma.model.StatsResponse
import com.example.dilemma.model.VoteBody
import com.example.dilemma.model.OptionStat
import com.example.dilemma.service.DilemmaService
import com.example.dilemma.service.dto.RecentDilemmaDto
import com.example.dilemma.service.dto.OptionDto
import com.example.rate.RateLimiter
import com.example.rate.clientIp
import com.example.rate.respondRateLimited
import com.example.util.TextSanitizer
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Query
import com.google.firebase.cloud.FirestoreClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private const val OTHER_OPTION_ID = "__other" // Diğer/Hiçbiri için özel id

@Serializable
data class CreateDilemmaBody(
    val title: String,
    val xLabel: String? = null,
    val yLabel: String? = null,
    val context: String? = null,
    val sourceUrl: String? = null,
    val topic: String? = null,
    val region: String? = null,

    // sınıflandırma
    val category: String? = null,       // football, politics, crypto, education, daily-life, ...
    val questionType: String? = null,   // prediction | advice | poll
    val language: String? = null,       // tr | en
    val visibility: String? = null      // public | followers | private-link (şimdilik mostly public)
)

/**
 * Firestore'daki bir dilemma dokümanını, sosyal/meta alanlarıyla birlikte
 * RecentDilemmaDto'ya map eder.
 *
 * Not: likedByMe + myPoints için ilgili alt koleksiyonlara sync call yapar.
 */
@Suppress("UNCHECKED_CAST")
private fun mapRecentDilemma(d: DocumentSnapshot, uid: String?): RecentDilemmaDto {
    fun L(n: String) = d.getLong(n)
    val x = L("xCount") ?: L("xcount") ?: 0L
    val y = L("yCount") ?: L("ycount") ?: 0L

    val likes = (d.getLong("likesCount") ?: 0L).toInt()
    val comments = (d.getLong("commentsCount") ?: 0L).toInt()
    val likedByMe = uid?.let { u ->
        d.reference.collection("likes").document(u).get().get().exists()
    } ?: false

    val type = d.getString("type")
    val rawOpts = (d.get("options") as? List<Map<String, Any?>>) ?: emptyList()
    val options = rawOpts.map { m ->
        OptionDto(
            id = (m["id"] as? String)?.trim().orEmpty(),
            label = (m["label"] as? String)?.trim().orEmpty(),
            count = ((m["count"] as? Number)?.toLong()) ?: 0L
        )
    }
    val isMulti = (type == "multi") || options.size >= 3

    // ---- Metadata alanları ----
    val category = d.getString("category") ?: "general"
    val questionType = d.getString("questionType") ?: "prediction"
    val language = d.getString("language") ?: "tr"
    val region = d.getString("region")
    val visibility = d.getString("visibility") ?: "public"

    // Çözüm & doğru cevap bilgileri
    val resolved = (d.getBoolean("resolved") == true)
    val correctXVal: Boolean? = if (!isMulti && resolved) (d.getBoolean("correctX") == true) else null
    val correctIdVal: String? = if (isMulti && resolved) d.getString("correctId") else null

    // Kullanıcının puanı (oy vermişse ve resolved ise)
    val myPoints: Int? = if (resolved && uid != null) {
        val v = d.reference.collection("votes").document(uid).get().get()
        if (v.exists()) (v.getLong("points") ?: 0L).toInt() else null
    } else null

    return RecentDilemmaDto(
        id = d.id,
        title = d.getString("title") ?: "",
        date = d.getString("date") ?: "",
        startedAt = L("startedAt") ?: 0L,
        expiresAt = L("expiresAt") ?: 0L,
        xCount = x,
        yCount = y,
        likesCount = likes,
        commentsCount = comments,
        likedByMe = likedByMe,
        type = if (isMulti) "multi" else (type ?: "binary"),
        options = options,
        category = category,
        questionType = questionType,
        language = language,
        region = region,
        visibility = visibility,
        resolved = resolved,
        correctX = correctXVal,
        correctId = correctIdVal,
        myPoints = myPoints
    )
}

@Suppress("UNCHECKED_CAST")
fun Route.dilemmaRoutes(service: DilemmaService) {

    /* Günün ikilemi */
    get("/dilemma/today") {
        val today = service.getDaily()
        if (today == null) call.respond(HttpStatusCode.NoContent)
        else               call.respond(today)
    }

    /* Kullanıcının kendi dilemmayı açması */
    post("/dilemma") {
        val uid = call.requireLogin() ?: return@post

        // 🔒 Rate limit: soru açmayı da sınırlayalım (UID 5rpm, IP 15rpm)
        val (okU, ru) = RateLimiter.allowUid(uid, 5)
        if (!okU) return@post call.respondRateLimited(ru, "rate-limited: uid")
        val (okI, ri) = RateLimiter.allowIp(call.clientIp(), 15)
        if (!okI) return@post call.respondRateLimited(ri, "rate-limited: ip")

        val body = call.receive<CreateDilemmaBody>()

        fun cleanOrNull(s: String?): String? =
            s?.trim()?.takeIf { it.isNotEmpty() }

        // ---- Title zorunlu + sanitize ----
        val rawTitle = body.title.trim()
        if (rawTitle.length < 4) {
            return@post call.respond(HttpStatusCode.BadRequest, "title_too_short")
        }
        val titleSanitized = TextSanitizer
            .sanitizeReason(rawTitle)
            .take(200)
            .ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, "invalid_title")
            }

        // ---- Opsiyonel alanlar (label, context, topic, region, sourceUrl) ----
        val xLabel = cleanOrNull(body.xLabel)?.take(40)
        val yLabel = cleanOrNull(body.yLabel)?.take(40)

        val context = cleanOrNull(body.context)
            ?.let { TextSanitizer.sanitizeReason(it) }
            ?.take(500)

        val sourceUrl = cleanOrNull(body.sourceUrl)?.take(400)
        val topic = cleanOrNull(body.topic)?.take(60)
        val region = cleanOrNull(body.region)?.take(32)

        // ---- Kategori normalizasyonu ----
        val categoryRaw = cleanOrNull(body.category)?.lowercase()
        val category = when (categoryRaw) {
            "football", "futbol"      -> "football"
            "politics", "siyaset"     -> "politics"
            "crypto", "kripto"        -> "crypto"
            "education", "egitim"     -> "education"
            "daily-life", "gundelik"  -> "daily-life"
            null                      -> "general"
            else                      -> "general"
        }

        // ---- questionType: prediction | advice | poll ----
        val qTypeRaw = cleanOrNull(body.questionType)?.lowercase()
        val questionType = when (qTypeRaw) {
            "prediction", "tahmin" -> "prediction"
            "advice", "danis"      -> "advice"
            "poll", "anket"        -> "poll"
            null                   -> "prediction"
            else                   -> "prediction"
        }

        // ---- language: tr | en ----
        val langRaw = cleanOrNull(body.language)?.lowercase()
        val language = when (langRaw) {
            "tr", "turkish", "türkçe" -> "tr"
            "en", "english"           -> "en"
            null                      -> "tr"
            else                      -> "tr"
        }

        // ---- visibility: public | followers | private-link ----
        val visRaw = cleanOrNull(body.visibility)?.lowercase()
        val visibility = when (visRaw) {
            "public"        -> "public"
            "followers"     -> "followers"
            "private-link"  -> "private-link"
            null            -> "public"
            else            -> "public"
        }

        // Domain: Submission hattıyla aynı mantık
        val id = service.createFromSubmission(
            title = titleSanitized,
            xLabel = xLabel,
            yLabel = yLabel,
            context = context,
            sourceUrl = sourceUrl,
            topic = topic,
            region = region,
            category = category,
            questionType = questionType,
            language = language,
            visibility = visibility
        )

        // createdBy bilgisini dokümana ekle (modeli değiştirmeden)
        val db = FirestoreClient.getFirestore()
        db.collection("dilemmas").document(id)
            .set(
                mapOf(
                    "createdBy" to uid,
                    "createdVia" to "user",
                    "type" to "binary",   // şimdilik user-created sorular binary
                    "visibility" to visibility,
                    "category" to category,
                    "questionType" to questionType,
                    "language" to language
                ),
                com.google.cloud.firestore.SetOptions.merge()
            )
            .get()

        call.respond(HttpStatusCode.Created, mapOf("id" to id))
    }

    /* Filtreli feed (kategori / tip / dil / bölge / visibility) */
    get("/dilemmas/feed") {
        val uid = call.uidOrNull() // opsiyonel (token varsa gelir)
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20

        fun qp(name: String): String? =
            call.request.queryParameters[name]
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }

        val category = qp("category")
        val questionType = qp("questionType")
        val language = qp("language")
        val region = qp("region")
        val visibilityParam = qp("visibility")

        // Default: sadece public feed. visibility=all derse filtreyi kaldırıyoruz.
        val effectiveVisibility: String? = when (visibilityParam) {
            null, "", "public" -> "public"
            "all" -> null
            else -> visibilityParam
        }

        val db = FirestoreClient.getFirestore()
        var q: Query = db.collection("dilemmas")

        if (category != null) {
            q = q.whereEqualTo("category", category)
        }
        if (questionType != null) {
            q = q.whereEqualTo("questionType", questionType)
        }
        if (language != null) {
            q = q.whereEqualTo("language", language)
        }
        if (region != null) {
            q = q.whereEqualTo("region", region)
        }
        if (effectiveVisibility != null) {
            q = q.whereEqualTo("visibility", effectiveVisibility)
        }

        q = q.orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)

        val docs = q.get().get().documents
        val out = docs.map { d -> mapRecentDilemma(d, uid) }

        call.respond(out)
    }

    /* Son ikilemler (+ sosyal meta + (ops.) multi seçenekler) */
    get("/dilemmas/recent") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 7
        val uid = call.uidOrNull() // opsiyonel (token varsa gelir)

        val db = FirestoreClient.getFirestore()
        val docs = db.collection("dilemmas")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.coerceIn(1, 30))
            .get().get().documents

        val out = docs.map { d -> mapRecentDilemma(d, uid) }

        call.respond(out)
    }

    /* Tekil ikilem işlemleri */
    route("/dilemma/{id}") {

        /* Oy verme (binary: choiceX | multi: optionId) + (ops.) gerekçe) */
        post("/vote") {
            val uid = call.requireLogin() ?: return@post
            val id  = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)

            // 🔒 Rate limit: UID 10rpm, IP 30rpm
            val (okU, ru) = RateLimiter.allowUid(uid, 10)
            if (!okU) return@post call.respondRateLimited(ru, "rate-limited: uid")
            val ip = call.clientIp()
            val (okI, ri) = RateLimiter.allowIp(ip, 30)
            if (!okI) return@post call.respondRateLimited(ri, "rate-limited: ip")

            val body = call.receive<VoteBody>()
            val db   = FirestoreClient.getFirestore()
            val dilemmaRef = db.collection("dilemmas").document(id)
            val now = System.currentTimeMillis()

            // A/B deney varyantı (header)
            val expGroupHeader = call.request.headers["X-Exp-Group"]?.trim()
            val variant = expGroupHeader?.take(8)

            // süre/çözüm kontrolü + tip/options tespiti
            val snap = dilemmaRef.get().get()
            if (!snap.exists()) return@post call.respond(HttpStatusCode.NotFound, "Dilemma not found")
            val resolved  = (snap.getBoolean("resolved") == true)
            val expiresAt = (snap.getLong("expiresAt") ?: 0L)
            if (resolved || (expiresAt > 0 && now > expiresAt)) {
                return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "deadline passed or resolved"))
            }

            val type = snap.getString("type") ?: "binary"
            val optsRaw = (snap.get("options") as? List<Map<String, Any?>>) ?: emptyList()
            val allowedIds = optsRaw.mapNotNull { (it["id"] as? String)?.trim() }.toSet()
            val isMulti = (type == "multi") || allowedIds.size >= 3

            // ---- ortak sanitize ----
            val conf = body.confidence?.coerceIn(0.5, 0.99)
            val reasonSanitized = body.reason?.let { TextSanitizer.sanitizeReason(it) }?.let { s ->
                if (s.any { it.isLetterOrDigit() }) s else null
            }

            val voteRef = dilemmaRef.collection("votes").document(uid)
            val userRef = db.collection("users").document(uid)

            // ✅ Transaction: binary & multi tek noktadan idempotent
            db.runTransaction { tx ->
                val now2 = System.currentTimeMillis()

                // --- READS ---
                val dSnap = tx.get(dilemmaRef).get()
                if (!dSnap.exists()) throw IllegalArgumentException("Dilemma not found")
                val res = (dSnap.getBoolean("resolved") == true)
                val exp = (dSnap.getLong("expiresAt") ?: 0L)
                if (res || (exp > 0 && now2 > exp)) throw IllegalStateException("deadline")

                val old = tx.get(voteRef).get()

                // Streak için user oku
                val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                val yesterday = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString()
                val uSnap = tx.get(userRef).get()
                val last = uSnap.getString("lastVoteDate")
                var streak = (uSnap.getLong("streak") ?: 0L).toInt()
                var best = (uSnap.getLong("bestStreak") ?: 0L).toInt()
                var shouldUpdateUser = false
                if (last != today) {
                    streak = if (last == yesterday) streak + 1 else 1
                    if (streak > best) best = streak
                    shouldUpdateUser = true
                }

                // --- WRITES ---
                val existingVariant = if (old.exists()) old.getString("variant") else null
                val variantToSave = existingVariant ?: variant

                if (!isMulti) {
                    // ===== BINARY PATH (choiceX zorunlu) =====
                    val newX = body.choiceX ?: throw IllegalArgumentException("choiceX required for binary vote")

                    fun inc(f: String, by: Long) = tx.update(dilemmaRef, f, FieldValue.increment(by))

                    val common = mutableMapOf<String, Any>(
                        "uid" to uid, "choiceX" to newX, "ts" to System.currentTimeMillis()
                    ).apply {
                        if (conf != null) put("confidence", conf)
                        if (reasonSanitized != null) put("reason", reasonSanitized)
                        if (!variantToSave.isNullOrBlank()) put("variant", variantToSave)
                    }

                    if (!old.exists()) {
                        tx.set(voteRef, common)
                        if (newX) inc("xCount", 1) else inc("yCount", 1)
                    } else {
                        val prevX = (old.getBoolean("choiceX") == true)
                        tx.update(voteRef, common)
                        if (prevX != newX) {
                            if (newX) { inc("xCount", 1); inc("yCount", -1) }
                            else      { inc("yCount", 1); inc("xCount", -1) }
                        }
                    }

                } else {
                    // ===== MULTI PATH (optionId zorunlu) =====
                    val newOptId = body.optionId?.trim().orEmpty()
                    if (newOptId.isEmpty()) throw IllegalArgumentException("optionId required for multi vote")
                    val isOther = (newOptId == OTHER_OPTION_ID)
                    if (!isOther && newOptId !in allowedIds) {
                        throw IllegalArgumentException("unknown optionId")
                    }

                    val common = mutableMapOf<String, Any>(
                        "uid" to uid, "optionId" to newOptId, "ts" to System.currentTimeMillis()
                    ).apply {
                        if (conf != null) put("confidence", conf)
                        if (reasonSanitized != null) put("reason", reasonSanitized) // (Diğer için metin burada)
                        if (!variantToSave.isNullOrBlank()) put("variant", variantToSave)
                    }

                    if (!old.exists()) tx.set(voteRef, common) else tx.update(voteRef, common)
                    // Not: doc seviyesinde per-option sayacı tutmuyoruz; /stats sırasında sayıyoruz.
                }

                if (shouldUpdateUser) {
                    tx.set(
                        userRef,
                        mapOf("lastVoteDate" to today, "streak" to streak, "bestStreak" to best),
                        com.google.cloud.firestore.SetOptions.merge()
                    )
                }

                null
            }.get()

            // transaction DIŞI: Binary ise reason'ı reasons'a yaz
            if (!isMulti && reasonSanitized != null) {
                dilemmaRef.collection("reasons").add(
                    mapOf(
                        "uid" to uid,
                        "choiceX" to (body.choiceX == true),
                        "text" to reasonSanitized,
                        "confidence" to (conf ?: 0.0),
                        "ts" to System.currentTimeMillis(),
                        "likes" to 0L
                    )
                ).get()
                dilemmaRef.update("commentsCount", FieldValue.increment(1L)).get()
            }

            // transaction DIŞI: Multi + Diğer/Hiçbiri ise, yoruma düşür
            if (isMulti && body.optionId?.trim() == OTHER_OPTION_ID && reasonSanitized != null) {
                // Comments alt koleksiyonu yoksa 'reasons' yerine 'comments' tercih ettik
                dilemmaRef.collection("comments").add(
                    mapOf(
                        "uid" to uid,
                        "type" to "other",
                        "otherLabel" to reasonSanitized,  // kullanıcının girdiği kısa seçenek
                        "text" to reasonSanitized,        // (ops.) aynı metni yorum gövdesi olarak da tut
                        "ts" to System.currentTimeMillis(),
                        "likes" to 0L
                    )
                ).get()
                dilemmaRef.update("commentsCount", FieldValue.increment(1L)).get()
            }

            // ⬇️ Micrometer sayacı (başarılı oy sonrası)
            val reg = call.application.attributes[PromRegistryKey]
            if (!isMulti) {
                reg.counter(
                    "votes_total",
                    "variant", (variant ?: "none"),
                    "choice", if (body.choiceX == true) "X" else "Y",
                    "with_reason", if (reasonSanitized != null) "yes" else "no"
                ).increment()
            } else {
                reg.counter(
                    "votes_total",
                    "variant", (variant ?: "none"),
                    "choice", "option:${body.optionId?.take(12) ?: "unknown"}",
                    "with_reason", if (reasonSanitized != null) "yes" else "no"
                ).increment()
            }

            call.respond(HttpStatusCode.NoContent)
        }

        /* Dilemma beğen / beğeniyi kaldır (toggle) */
        post("/like") {
            val uid = call.requireLogin() ?: return@post
            val id  = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)

            // 🔒 Rate limit: UID 10rpm, IP 30rpm
            val (okU, ru) = RateLimiter.allowUid(uid, 10)
            if (!okU) return@post call.respondRateLimited(ru, "rate-limited: uid")
            val (okI, ri) = RateLimiter.allowIp(call.clientIp(), 30)
            if (!okI) return@post call.respondRateLimited(ri, "rate-limited: ip")

            val db = FirestoreClient.getFirestore()
            val dref = db.collection("dilemmas").document(id)
            val likeRef = dref.collection("likes").document(uid)

            val result = db.runTransaction { tx ->
                val dSnap = tx.get(dref).get()
                if (!dSnap.exists()) throw IllegalArgumentException("Dilemma not found")

                val likedSnap = tx.get(likeRef).get()
                var likes = (dSnap.getLong("likesCount") ?: 0L).toInt()

                val nowLiked: Boolean
                if (likedSnap.exists()) {
                    tx.delete(likeRef)
                    likes = (likes - 1).coerceAtLeast(0)
                    nowLiked = false
                } else {
                    tx.set(likeRef, mapOf("uid" to uid, "ts" to System.currentTimeMillis()))
                    likes += 1
                    nowLiked = true
                }
                tx.update(dref, "likesCount", likes)
                mapOf("liked" to nowLiked, "likes" to likes)
            }.get()

            // metrik
            val reg = call.application.attributes[PromRegistryKey]
            val likedVal = (result["liked"] as? Boolean) ?: false
            reg.counter("dilemma_likes_total", "action", if (likedVal) "like" else "unlike").increment()

            call.respond(result)
        }

        /* İstatistik — Binary: x/y, Multi: options[] */
        get("/stats") {
            call.requireLogin() ?: return@get
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val db = FirestoreClient.getFirestore()
            val doc = db.collection("dilemmas").document(id).get().get()
            if (!doc.exists()) return@get call.respond(HttpStatusCode.NotFound)

            val type = doc.getString("type") ?: "binary"
            val isMulti = type == "multi" || (((doc.get("options") as? List<*>)?.size ?: 0) >= 3)

            if (!isMulti) {
                val x: Long = (doc.getLong("xCount") ?: doc.getLong("xcount") ?: 0L)
                val y: Long = (doc.getLong("yCount") ?: doc.getLong("ycount") ?: 0L)
                val total = (x + y).coerceAtLeast(1L).toDouble()
                val pctX = x.toDouble() * 100.0 / total
                val pctY = 100.0 - pctX

                call.respond(StatsResponse(x = x, y = y, pctX = pctX, pctY = pctY, options = null))
                return@get
            }

            // MULTI: options listesini oku ve oyları say
            @Suppress("UNCHECKED_CAST")
            val rawOpts = (doc.get("options") as? List<Map<String, Any?>>) ?: emptyList()
            val options = rawOpts.map { m ->
                OptionDto(
                    id = (m["id"] as? String)?.trim().orEmpty(),
                    label = (m["label"] as? String)?.trim().orEmpty(),
                    count = ((m["count"] as? Number)?.toLong()) ?: 0L
                )
            }

            val votesCol = db.collection("dilemmas").document(id).collection("votes")
            var total = 0L
            val stats = mutableListOf<OptionStat>()
            for (o in options) {
                val cnt = votesCol.whereEqualTo("optionId", o.id).get().get().documents.size.toLong()
                total += cnt
                stats += OptionStat(id = o.id, label = o.label, count = cnt, pct = 0.0)
            }
            val totalD = total.coerceAtLeast(1L).toDouble()
            val finalStats = stats.map { s -> s.copy(pct = (s.count.toDouble() * 100.0 / totalD)) }

            call.respond(
                StatsResponse(
                    x = 0, y = 0, pctX = 0.0, pctY = 0.0,
                    options = finalStats
                )
            )
        }

        /* Gerekçeler – (binary kalır) */
        get("/reasons") {
            val uid = call.requireLogin() ?: return@get
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 3

            val db = FirestoreClient.getFirestore()
            val col = db.collection("dilemmas").document(id).collection("reasons")

            fun mapDoc(d: com.google.cloud.firestore.DocumentSnapshot): Map<String, Any?> {
                val likes = (d.getLong("likes") ?: 0L).toInt()
                val liked = d.reference.collection("likes").document(uid).get().get().exists()
                return mapOf(
                    "id" to d.id,
                    "text" to (d.getString("text") ?: ""),
                    "confidence" to ((d.getDouble("confidence") ?: 0.0)),
                    "ts" to (d.getLong("ts") ?: 0L),
                    "likes" to likes,
                    "liked" to liked
                )
            }

            val xDocs = col.whereEqualTo("choiceX", true)
                .orderBy("ts", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .limit(limit).get().get().documents.map(::mapDoc)

            val yDocs = col.whereEqualTo("choiceX", false)
                .orderBy("ts", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .limit(limit).get().get().documents.map(::mapDoc)

            call.respond(mapOf("x" to xDocs, "y" to yDocs))
        }

        /* Gerekçeyi beğen / beğeniyi kaldır (toggle) */
        post("/reasons/{rid}/like") {
            val uid = call.requireLogin() ?: return@post
            val id  = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val rid = call.parameters["rid"] ?: return@post call.respond(HttpStatusCode.BadRequest)

            // 🔒 Rate limit: UID 10rpm, IP 30rpm
            val (okU, ru) = RateLimiter.allowUid(uid, 10)
            if (!okU) return@post call.respondRateLimited(ru, "rate-limited: uid")
            val ip = call.clientIp()
            val (okI, ri) = RateLimiter.allowIp(ip, 30)
            if (!okI) return@post call.respondRateLimited(ri, "rate-limited: ip")

            val db = FirestoreClient.getFirestore()
            val reasonRef = db.collection("dilemmas").document(id)
                .collection("reasons").document(rid)
            val likeRef = reasonRef.collection("likes").document(uid)

            val result = db.runTransaction { tx ->
                val rSnap = tx.get(reasonRef).get()
                if (!rSnap.exists()) throw IllegalArgumentException("Reason not found")
                var likes = (rSnap.getLong("likes") ?: 0L).toInt()

                val likedSnap = tx.get(likeRef).get()
                val liked: Boolean
                if (likedSnap.exists()) {
                    tx.delete(likeRef)
                    likes = (likes - 1).coerceAtLeast(0)
                    liked = false
                } else {
                    tx.set(likeRef, mapOf("uid" to uid, "ts" to System.currentTimeMillis()))
                    likes += 1
                    liked = true
                }
                tx.update(reasonRef, "likes", likes)
                mapOf("liked" to liked, "likes" to likes)
            }.get()

            // ⬇️ Micrometer sayacı (like/unlike sonrası)
            val reg = call.application.attributes[PromRegistryKey]
            val likedVal = (result["liked"] as? Boolean) ?: false
            reg.counter(
                "reasons_likes_total",
                "action", if (likedVal) "like" else "unlike"
            ).increment()

            call.respond(result)
        }

        /* Benzer sorular (aynı kategori + dil, tarihine göre yakınlar) */
        get("/similar") {
            val uid = call.uidOrNull()
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val limit = call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, 20)
                ?: 5

            val db = FirestoreClient.getFirestore()
            val currentRef = db.collection("dilemmas").document(id)
            val current = currentRef.get().get()
            if (!current.exists()) {
                return@get call.respond(HttpStatusCode.NotFound, "Dilemma not found")
            }

            val category = current.getString("category") ?: "general"
            val language = current.getString("language") ?: "tr"

            var q: Query = db.collection("dilemmas")
                .whereEqualTo("category", category)
                .whereEqualTo("language", language)

            q = q.orderBy("createdAt", Query.Direction.DESCENDING)
                // oversample biraz, sonra kendisini ve benzer olmayanları atabiliriz
                .limit((limit * 3).coerceAtMost(60))

            val docs = q.get().get().documents
                .filter { it.id != id }  // kendisini çıkar
                .take(limit)

            val out = docs.map { d -> mapRecentDilemma(d, uid) }
            call.respond(out)
        }
    }
}
