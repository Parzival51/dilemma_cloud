// file: src/main/kotlin/com/example/dilemma/service/DilemmaService.kt
package com.example.dilemma.service

import com.example.dilemma.model.Dilemma
import com.example.dilemma.model.DilemmaStats
import com.example.dilemma.repository.FirestoreDilemmaRepo
import com.example.dilemma.service.dto.SummaryDto
import com.example.dilemma.service.dto.CategorySummaryDto
import java.util.concurrent.TimeUnit

class DilemmaService(
    private val repo: FirestoreDilemmaRepo
) {
    /* ---------------- Daily dilemma lifecycle ---------------- */

    // Eski "günün ikilemi" oluşturma fonksiyonun – CLI vb. hâlâ burayı kullanabilir
    suspend fun createDaily(title: String): String {
        val now     = System.currentTimeMillis()
        val expires = now + TimeUnit.HOURS.toMillis(48)

        val dilemm = Dilemma(
            title     = title,
            startedAt = now,
            expiresAt = expires
        )
        return repo.add(dilemm)
    }

    /**
     * Yeni: Submission’dan gelen tüm metadata ile dilemmma oluşturur.
     * User-generated prediction / advice / poll sorular bu yolla publish edilecek.
     *
     * Not: Default ve normalizasyon kuralları burada toplu uygulanır.
     */
    suspend fun createFromSubmission(
        title: String,
        xLabel: String?,
        yLabel: String?,
        context: String?,
        sourceUrl: String?,
        topic: String?,
        region: String?,
        category: String?,
        questionType: String?,
        language: String?,
        visibility: String?
    ): String {
        val now     = System.currentTimeMillis()
        val expires = now + TimeUnit.HOURS.toMillis(48)

        // Basit normalizasyon + default’lar
        val normCategory = category
            ?.trim()
            ?.lowercase()
            .takeIf { !it.isNullOrEmpty() }
            ?: "general"

        val normQType = questionType
            ?.trim()
            ?.lowercase()
            .takeIf { !it.isNullOrEmpty() }
            ?: "prediction"

        val normLang = language
            ?.trim()
            ?.lowercase()
            .takeIf { !it.isNullOrEmpty() }
            ?: "tr"

        val normVis = visibility
            ?.trim()
            ?.lowercase()
            .takeIf { !it.isNullOrEmpty() }
            ?: "public"

        val dilemm = Dilemma(
            title        = title,
            startedAt    = now,
            expiresAt    = expires,

            // Submission metadata
            xLabel       = xLabel,
            yLabel       = yLabel,
            context      = context,
            sourceUrl    = sourceUrl,
            topic        = topic,
            region       = region,
            category     = normCategory,
            questionType = normQType,
            language     = normLang,
            visibility   = normVis
        )

        return repo.add(dilemm)
    }

    suspend fun getDaily(): Dilemma? = repo.getToday()
    suspend fun today(): Dilemma? = repo.getToday()

    suspend fun vote(dilemmaId: String, userId: String, choiceX: Boolean) {
        require(dilemmaId.isNotBlank()) { "id boş olamaz" }
        repo.vote(dilemmaId, userId, choiceX)
    }

    suspend fun stats(dilemmaId: String): DilemmaStats =
        repo.getStats(dilemmaId) ?: DilemmaStats(0, 0)

    suspend fun recent(limit: Int): List<Dilemma> = repo.getRecent(limit)

    /* ---------------- User / leaderboard / reasons ---------------- */

    // /me/votes
    suspend fun getUserVotes(uid: String, limit: Int) =
        repo.listUserVotes(uid, limit)

    // /me/score
    suspend fun getUserScore(uid: String, awardPending: Boolean = false) =
        repo.getUserScore(uid)   // resolve sırasında puan yazıldığı için ek award yapmıyoruz.

    // /leaderboard
    suspend fun getLeaderboard(limit: Int) =
        repo.getLeaderboard(limit)

    // /dilemma/{id}/reasons
    suspend fun getReasons(dilemmaId: String, viewerUid: String, limit: Int) =
        repo.getReasons(dilemmaId, viewerUid, limit)

    // POST /dilemma/{id}/reasons/{rid}/like
    suspend fun toggleReasonLike(dilemmaId: String, reasonId: String, uid: String) =
        repo.toggleReasonLike(dilemmaId, reasonId, uid)

    // /me/summary (genişletilmiş + abonelik tier'ına göre gating)
    suspend fun getUserSummary(uid: String): SummaryDto =
        repo.getUserSummary(uid)

    // /me/category-summary (kategori bazlı performans + abonelik tier'ına göre gating)
    suspend fun getUserCategorySummary(uid: String): CategorySummaryDto =
        repo.getUserCategorySummary(uid)
}
