// file: src/main/kotlin/com/example/dilemma/repository/FirestoreDilemmaRepo.kt
package com.example.dilemma.repository

import com.example.dilemma.model.Dilemma
import com.example.dilemma.model.DilemmaStats
import com.example.dilemma.service.dto.*
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FirestoreDilemmaRepo(
    private val db: Firestore
) : DilemmaRepository {

    /* ---------------- Core (mevcut) ---------------- */

    override suspend fun add(dilemma: Dilemma): String {
        val now = System.currentTimeMillis()
        val doc = db.collection("dilemmas")
            .add(dilemma.copy(id = ""))
            .get()

        // Admin listeleri ve filtreler için gerekli alanları garanti et
        val createdAt = if (dilemma.startedAt > 0) dilemma.startedAt else now
        doc.update(
            mapOf(
                "id" to doc.id,
                "createdAt" to createdAt,
                "resolved" to false
            )
        ).get()

        return doc.id
    }

    override suspend fun getToday(): Dilemma? {
        val todayKey = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()

        val snap = db.collection("dilemmas")
            .whereEqualTo("date", todayKey)
            .limit(1)
            .get().get()

        if (snap.isEmpty) return null
        val doc = snap.documents.first()
        fun getL(name: String) = doc.getLong(name)

        val x = getL("xCount") ?: getL("xcount") ?: 0L
        val y = getL("yCount") ?: getL("ycount") ?: 0L

        // ---- Metadata alanlarını dokümandan oku (yoksa default’a düş) ----
        val category = doc.getString("category") ?: "general"
        val questionType = doc.getString("questionType") ?: "prediction"
        val language = doc.getString("language") ?: "tr"
        val region = doc.getString("region")
        val visibility = doc.getString("visibility") ?: "public"

        // 🔹 M4.1: Submission metadata’sını da Dilemma’ya taşı
        val xLabel = doc.getString("xLabel")
        val yLabel = doc.getString("yLabel")
        val context = doc.getString("context")
        val sourceUrl = doc.getString("sourceUrl")
        val topic = doc.getString("topic")
        val sponsorId = doc.getString("sponsorId")

        return Dilemma(
            id        = doc.id,
            title     = doc.getString("title") ?: "",
            date      = doc.getString("date") ?: todayKey,
            startedAt = getL("startedAt") ?: 0L,
            expiresAt = getL("expiresAt") ?: 0L,
            xCount    = x,
            yCount    = y,
            prosCons  = null,

            // submission’dan gelen metadata
            xLabel    = xLabel,
            yLabel    = yLabel,
            context   = context,
            sourceUrl = sourceUrl,
            topic     = topic,
            region    = region,

            // sınıflandırma alanları
            category      = category,
            questionType  = questionType,
            language      = language,
            visibility    = visibility,

            // B2B / sponsor
            sponsorId = sponsorId
        )
    }

    override suspend fun vote(dilemmaId: String, userId: String, choiceX: Boolean) {
        val dilemmaRef = db.collection("dilemmas").document(dilemmaId)
        val voteRef    = dilemmaRef.collection("votes").document(userId)

        db.runTransaction { tx ->
            val prevSnap: DocumentSnapshot = tx.get(voteRef).get()
            val prevChoice = if (prevSnap.exists()) prevSnap.getBoolean("choiceX") else null
            if (prevChoice == choiceX) return@runTransaction null

            val incField = if (choiceX) "xCount" else "yCount"
            val decField = if (choiceX) "yCount" else "xCount"

            tx.update(dilemmaRef, incField, FieldValue.increment(1))
            if (prevChoice != null) tx.update(dilemmaRef, decField, FieldValue.increment(-1))

            tx.set(
                voteRef,
                mapOf(
                    "uid"      to userId,
                    "choiceX"  to choiceX,
                    "ts"       to System.currentTimeMillis()
                )
            )
            null
        }.get()
    }

    override suspend fun getStats(dilemmaId: String): DilemmaStats? {
        val doc = db.collection("dilemmas").document(dilemmaId).get().get()
        if (!doc.exists()) return null
        val x = doc.getLong("xCount") ?: doc.getLong("xcount") ?: 0L
        val y = doc.getLong("yCount") ?: doc.getLong("ycount") ?: 0L
        return DilemmaStats(x, y)
    }

    override suspend fun getRecent(limit: Int): List<Dilemma> {
        val snap = db.collection("dilemmas")
            // 🔧 patch: "date" yerine "createdAt" ile sırala
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .get().get()

        return snap.documents.map { doc ->
            fun L(n: String) = doc.getLong(n)
            val x = L("xCount") ?: L("xcount") ?: 0L
            val y = L("yCount") ?: L("ycount") ?: 0L

            // ---- Metadata alanlarını dokümandan oku (yoksa default’a düş) ----
            val category = doc.getString("category") ?: "general"
            val questionType = doc.getString("questionType") ?: "prediction"
            val language = doc.getString("language") ?: "tr"
            val region = doc.getString("region")
            val visibility = doc.getString("visibility") ?: "public"

            // 🔹 M4.1: Submission metadata’sını da doldur
            val xLabel = doc.getString("xLabel")
            val yLabel = doc.getString("yLabel")
            val context = doc.getString("context")
            val sourceUrl = doc.getString("sourceUrl")
            val topic = doc.getString("topic")
            val sponsorId = doc.getString("sponsorId")

            Dilemma(
                id        = doc.id,
                title     = doc.getString("title") ?: "",
                date      = doc.getString("date") ?: "",
                startedAt = L("startedAt") ?: 0L,
                expiresAt = L("expiresAt") ?: 0L,
                xCount    = x,
                yCount    = y,
                prosCons  = null,

                xLabel    = xLabel,
                yLabel    = yLabel,
                context   = context,
                sourceUrl = sourceUrl,
                topic     = topic,
                region    = region,

                category      = category,
                questionType  = questionType,
                language      = language,
                visibility    = visibility,
                sponsorId     = sponsorId
            )
        }
    }

    /* ---------------- User / score / leaderboard ---------------- */

    /** Kullanıcının son ~N ikilemdeki oy kayıtları (History için). */
    suspend fun listUserVotes(uid: String, limit: Int): List<Map<String, Any?>> {
        val dilemmas = db.collection("dilemmas")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .get().get()

        val out = mutableListOf<Map<String, Any?>>()
        for (d in dilemmas.documents) {
            val voteRef = d.reference.collection("votes").document(uid)
            val voteSnap = voteRef.get().get()
            if (!voteSnap.exists()) continue

            val title = d.getString("title") ?: ""
            val xCount = (d.getLong("xCount") ?: 0L).toInt()
            val yCount = (d.getLong("yCount") ?: 0L).toInt()
            val total = (xCount + yCount).coerceAtLeast(1)
            val xPct = xCount * 100.0 / total
            val yPct = 100.0 - xPct

            val ts = (voteSnap.getLong("ts") ?: 0L)
            val dt = java.time.Instant.ofEpochMilli(ts)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            val date = dt.format(DateTimeFormatter.ISO_LOCAL_DATE)

            val choiceX = voteSnap.getBoolean("choiceX") == true
            val resolved = d.getBoolean("resolved")
            val correctX = d.getBoolean("correctX")
            val correct = resolved?.let { r -> if (!r) null else (correctX == choiceX) }
            val points = (voteSnap.getLong("points") ?: 0L).toInt().takeIf { it > 0 }

            out += mapOf(
                "id" to d.id, "title" to title, "date" to date,
                "choiceX" to choiceX, "xPct" to xPct, "yPct" to yPct, "ts" to ts,
                "resolved" to resolved, "correct" to correct, "correctX" to correctX, "points" to points
            )
        }
        return out.sortedByDescending { (it["ts"] as Long? ?: 0L) }
    }

    /** users/{uid} -> score, streak, bestStreak alanları */
    suspend fun getUserScore(uid: String): Map<String, Int> {
        val u = db.collection("users").document(uid).get().get()
        val score = (u.getLong("score") ?: 0L).toInt()
        val streak = (u.getLong("streak") ?: 0L).toInt()
        val best = (u.getLong("bestStreak") ?: 0L).toInt()
        return mapOf("score" to score, "streak" to streak, "bestStreak" to best)
    }

    /** All-time leaderboard (score desc) */
    suspend fun getLeaderboard(limit: Int): List<Map<String, Any>> {
        val snaps = db.collection("users")
            .orderBy("score", Query.Direction.DESCENDING)
            .limit(limit)
            .get().get()

        var rank = 0
        return snaps.documents.map { d ->
            rank += 1
            mapOf(
                "rank" to rank,
                "uid" to d.id,
                "score" to (d.getLong("score") ?: 0L).toInt(),
                "streak" to (d.getLong("streak") ?: 0L).toInt(),
                "bestStreak" to (d.getLong("bestStreak") ?: 0L).toInt()
            )
        }
    }

    /* ---------------- Reasons (liste + like toggle) ---------------- */

    suspend fun getReasons(dilemmaId: String, viewerUid: String, limit: Int): Map<String, Any> {
        val base = db.collection("dilemmas").document(dilemmaId).collection("reasons")

        fun fetch(choiceX: Boolean): List<Map<String, Any?>> {
            val qs = base.whereEqualTo("choiceX", choiceX)
                .orderBy("likes", Query.Direction.DESCENDING)
                .limit(limit)
                .get().get()
            val out = mutableListOf<Map<String, Any?>>()
            for (d in qs.documents) {
                val text = d.getString("text") ?: ""
                val likes = (d.getLong("likes") ?: 0L).toInt()
                val conf = d.getDouble("confidence") ?: 0.0
                val liked = d.reference.collection("likes").document(viewerUid).get().get().exists()
                out += mapOf("id" to d.id, "text" to text, "likes" to likes, "liked" to liked, "confidence" to conf)
            }
            return out
        }

        return mapOf(
            "x" to fetch(true),
            "y" to fetch(false)
        )
    }

    suspend fun toggleReasonLike(dilemmaId: String, reasonId: String, uid: String): Map<String, Any> {
        val rref = db.collection("dilemmas").document(dilemmaId)
            .collection("reasons").document(reasonId)
        val likeRef = rref.collection("likes").document(uid)

        val result = db.runTransaction { tx ->
            val like = tx.get(likeRef).get()
            val cur  = tx.get(rref).get()
            var likes = (cur.getLong("likes") ?: 0L).toInt()

            val nowLiked: Boolean
            if (like.exists()) {
                // unlike
                tx.delete(likeRef)
                likes = (likes - 1).coerceAtLeast(0)
                nowLiked = false
            } else {
                // like
                tx.set(likeRef, mapOf("ts" to System.currentTimeMillis()))
                likes += 1
                nowLiked = true
            }
            tx.update(rref, mapOf("likes" to likes))
            mapOf("liked" to nowLiked, "likes" to likes)
        }.get()

        @Suppress("UNCHECKED_CAST")
        return result as Map<String, Any>
    }

    /* ---------------- Küçük helper: abonelik tier'ını oku ---------------- */

    // Kullanıcının abonelik tier'ını ("free" | "plus" | "pro" ...) okuyan küçük yardımcı.
    private fun loadUserTier(uid: String): String {
        val u = db.collection("users").document(uid).get().get()
        return (u.getString("subscriptionTier") ?: "free").lowercase()
    }

    /* ---------------- Summary (genişletilmiş) ---------------- */

    suspend fun getUserSummary(uid: String): SummaryDto {
        val subscriptionTier = loadUserTier(uid)

        // Kullanıcının timezone offset'ini (dakika) dene; yoksa 0 (UTC)
        val userRef = db.collection("users").document(uid)
        val tokenSnap = userRef.collection("fcmTokens")
            .whereEqualTo("enabled", true)
            .limit(1)
            .get().get()
        val tzOffsetMinutes: Int = tokenSnap.documents.firstOrNull()
            ?.getLong("tzOffsetMinutes")?.toInt() ?: 0
        val tzOffsetMillis = tzOffsetMinutes * 60_000L

        // Son ~180 ikilemi tara (performans/hesap dengesi)
        val dilemmas = db.collection("dilemmas")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(180)
            .get().get()

        var total = 0
        var correct = 0
        var confSum = 0.0
        var confCount = 0
        var ttvSumSec = 0L
        var ttvCount = 0

        // Kalibrasyon için 5 kova: [0.5,0.6) ... [0.9,1.0]
        val bucketRanges = arrayOf("0.5-0.6","0.6-0.7","0.7-0.8","0.8-0.9","0.9-1.0")
        val bN = IntArray(5) { 0 }
        val bConfSum = DoubleArray(5) { 0.0 }
        val bCorrect = IntArray(5) { 0 }

        // Saat histogramı (kullanıcının timezone'una göre)
        val hourVotes = IntArray(24) { 0 }
        val hourCorrect = IntArray(24) { 0 }

        fun bucketIndex(c: Double): Int = when {
            c < 0.5 -> -1
            c < 0.6 -> 0
            c < 0.7 -> 1
            c < 0.8 -> 2
            c < 0.9 -> 3
            else -> 4
        }

        for (d in dilemmas.documents) {
            val v = d.reference.collection("votes").document(uid).get().get()
            if (!v.exists()) continue

            total += 1

            val resolved = d.getBoolean("resolved") == true

            // ----- Dilemma tipini tespit et -----
            val type = (d.getString("type") ?: "binary").lowercase()
            val isMulti = type == "multi" ||
                    (((d.get("options") as? List<*>)?.size ?: 0) >= 3)

            // ----- Doğruluk hesabı (binary vs multi) -----
            val isCorrect: Boolean = if (!resolved) {
                false
            } else if (!isMulti) {
                val choiceX = v.getBoolean("choiceX") == true
                val correctX = d.getBoolean("correctX") == true
                choiceX == correctX
            } else {
                val optionId = v.getString("optionId")
                val correctId = d.getString("correctId")
                (optionId != null && correctId != null && optionId == correctId)
            }
            if (isCorrect) correct += 1

            // ----- Confidence (her iki tipte de aynı) -----
            val c = v.getDouble("confidence")
            if (c != null && c in 0.0..1.0) {
                confSum += c
                confCount += 1
                val bi = bucketIndex(c)
                if (bi >= 0) {
                    bN[bi] += 1
                    bConfSum[bi] += c
                    if (isCorrect) bCorrect[bi] += 1
                }
            }

            // ----- Ortalama cevap süresi -----
            val ts = (v.getLong("ts") ?: 0L)
            val started = (d.getLong("startedAt") ?: 0L)
            if (ts > 0 && started > 0 && ts >= started) {
                ttvSumSec += ((ts - started) / 1000L)
                ttvCount += 1
            }

            // ----- Saatlik histogram (local) -----
            if (ts > 0) {
                val localTs = ts + tzOffsetMillis
                val hour = java.time.Instant.ofEpochMilli(localTs)
                    .atZone(java.time.ZoneOffset.UTC).hour
                hourVotes[hour] += 1
                if (isCorrect) hourCorrect[hour] += 1
            }
        }

        val accuracy = if (total > 0) correct.toDouble() / total else 0.0
        val avgConf: Double? = if (confCount > 0) confSum / confCount else null
        val calibGap: Double? = avgConf?.let { kotlin.math.abs(it - accuracy) }
        val avgTtv: Long? = if (ttvCount > 0) ttvSumSec / ttvCount else null

        // Kovaları hazırla
        val buckets = (0 until 5).map { i ->
            val n = bN[i]
            val ac = if (n > 0) bConfSum[i] / n else null
            val acc = if (n > 0) bCorrect[i].toDouble() / n else null
            CalibrationBucketDto(
                range = bucketRanges[i],
                n = n,
                avgConfidence = ac,
                accuracy = acc
            )
        }

        // En iyi 3 saatlik pencere
        val minVotesForWindow = 3
        var best: BestHourRangeDto? = null
        for (h in 0 until 24) {
            val hours = listOf(h, (h + 1) % 24, (h + 2) % 24)
            val vSum = hours.sumOf { hourVotes[it] }
            if (vSum < minVotesForWindow) continue
            val cSum = hours.sumOf { hourCorrect[it] }
            val acc = if (vSum > 0) cSum.toDouble() / vSum else 0.0

            if (best == null ||
                acc > best!!.accuracy + 1e-9 ||
                (kotlin.math.abs(acc - best!!.accuracy) < 1e-9 && vSum > best!!.votes)
            ) {
                best = BestHourRangeDto(
                    startHour = h,
                    endHour = (h + 3) % 24,
                    votes = vSum,
                    accuracy = acc
                )
            }
        }

        val full = SummaryDto(
            totalVotes = total,
            correctVotes = correct,
            accuracy = accuracy,
            avgConfidence = avgConf,
            calibrationGap = calibGap,
            avgTimeToVoteSec = avgTtv,
            calibrationBuckets = buckets,
            timeOfDay = hourVotes.toList(),
            bestHourRange = best
        )

        // Abonelik tier'ına göre (free vs plus/pro) dönen alanları sınırla.
        // free: sadece temel metrikler + avgTimeToVoteSec, gelişmiş grafikleri saklıyoruz.
        return if (subscriptionTier == "free") {
            full.copy(
                avgConfidence = null,
                calibrationGap = null,
                calibrationBuckets = emptyList(),
                timeOfDay = List(24) { 0 },
                bestHourRange = null
            )
        } else {
            full
        }
    }

    /**
     * Kullanıcının kategori bazlı performans özeti.
     * Son ~300 ikileme bakıp, hangi kategoride kaç oy / kaç doğru / ortalama confidence vs. çıkarıyoruz.
     */
    suspend fun getUserCategorySummary(uid: String): CategorySummaryDto {
        // Son ~300 ikilemi tara (performans / hesap dengesi)
        val dilemmas = db.collection("dilemmas")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(300)
            .get().get()

        data class Agg(
            var total: Int = 0,
            var correct: Int = 0,
            var confSum: Double = 0.0,
            var confCount: Int = 0
        )

        val byCategory = mutableMapOf<String, Agg>()

        fun normalizeCategory(raw: String?): String {
            val v = (raw ?: "general").trim()
            if (v.isEmpty()) return "general"
            // Kaydı map key'inde lowercase tutuyoruz ama UI'da istersen map'leyebilirsin.
            return v.lowercase()
        }

        for (d in dilemmas.documents) {
            val vote = d.reference.collection("votes").document(uid).get().get()
            if (!vote.exists()) continue

            val catKey = normalizeCategory(d.getString("category"))
            val agg = byCategory.getOrPut(catKey) { Agg() }

            agg.total += 1

            val resolved = d.getBoolean("resolved") == true

            // Dilemma tipini tespit et (binary vs multi)
            val type = (d.getString("type") ?: "binary").lowercase()
            val isMulti = type == "multi" ||
                    (((d.get("options") as? List<*>)?.size ?: 0) >= 3)

            val isCorrect: Boolean = if (!resolved) {
                false
            } else if (!isMulti) {
                val choiceX = vote.getBoolean("choiceX") == true
                val correctX = d.getBoolean("correctX") == true
                choiceX == correctX
            } else {
                val optionId = vote.getString("optionId")
                val correctId = d.getString("correctId")
                optionId != null && correctId != null && optionId == correctId
            }

            if (isCorrect) {
                agg.correct += 1
            }

            val c = vote.getDouble("confidence")
            if (c != null && c in 0.0..1.0) {
                agg.confSum += c
                agg.confCount += 1
            }
        }

        val items = byCategory.entries
            .map { (key, a) ->
                val accuracy = if (a.total > 0) a.correct.toDouble() / a.total.toDouble() else 0.0
                val avgConf: Double? =
                    if (a.confCount > 0) a.confSum / a.confCount.toDouble() else null
                val gap: Double? = avgConf?.let { kotlin.math.abs(it - accuracy) }

                CategorySummaryItemDto(
                    category = key,
                    totalVotes = a.total,
                    correctVotes = a.correct,
                    accuracy = accuracy,
                    avgConfidence = avgConf,
                    calibrationGap = gap
                )
            }
            // En çok oy kullanılan kategoriler üstte
            .sortedWith(
                compareByDescending<CategorySummaryItemDto> { it.totalVotes }
                    .thenBy { it.category }
            )

        val subscriptionTier = loadUserTier(uid)

        val gatedItems =
            if (subscriptionTier == "free") {
                // Free: en çok oy aldığın ilk 2 kategori, basit istatistikler.
                items
                    .take(2)
                    .map { it.copy(avgConfidence = null, calibrationGap = null) }
            } else {
                // plus / pro: full liste + tüm alanlar
                items
            }

        return CategorySummaryDto(items = gatedItems)
    }
}
