// file: src/main/kotlin/com/example/submission/service/SubmissionService.kt
package com.example.submission.service

import com.example.dilemma.service.DilemmaService
import com.example.notifications.NotificationService
import com.example.submission.model.Submission
import com.example.submission.repository.SubmissionRepository
import com.example.util.TextSanitizer

class SubmissionService(
    private val repo: SubmissionRepository,
    private val dilemmas: DilemmaService
) {

    suspend fun createDraft(
        uid: String,
        title: String,
        x: String?,
        y: String?,
        ctx: String?,
        src: String?,
        topic: String?,
        region: String?,
        category: String?,
        questionType: String?,
        language: String?,
        visibility: String?
    ): String = repo.createDraft(
        uid,
        Submission(
            title = title,
            xLabel = x,
            yLabel = y,
            context = ctx,
            sourceUrl = src,
            topic = topic,
            region = region,
            category = category,
            questionType = questionType,
            language = language,
            visibility = visibility
        )
    )

    suspend fun updateDraft(uid: String, id: String, patch: Map<String, Any?>) =
        repo.updateDraft(uid, id, patch)

    suspend fun submit(uid: String, id: String) =
        repo.submit(uid, id)

    suspend fun mine(uid: String) = repo.listMine(uid)

    suspend fun pending(limit: Int) = repo.listByStatus("pending", limit)

    /** Admin inline patch (status değişmez) */
    suspend fun adminPatch(id: String, patch: Map<String, Any?>) =
        repo.adminPatch(id, patch)

    /**
     * Admin onayı:
     *  - Submission'dan metadata'yı alır
     *  - Tam özellikli Dilemma oluşturur
     *  - Submission'ı approved olarak işaretler
     *  - Kullanıcıya bildirim gönderir
     */
    suspend fun approve(submissionId: String, reviewerId: String): String {
        val sub = repo.getById(submissionId) ?: error("Submission not found")
        require(sub.status == "pending") { "Not pending" }

        val safeTitle = TextSanitizer.sanitizeTitle(sub.title)

        // Artık sadece title değil, tüm metadata ile Dilemma yaratıyoruz
        val newId = dilemmas.createFromSubmission(
            title = safeTitle,
            xLabel = sub.xLabel,
            yLabel = sub.yLabel,
            context = sub.context,
            sourceUrl = sub.sourceUrl,
            topic = sub.topic,
            region = sub.region,
            category = sub.category,
            questionType = sub.questionType,
            language = sub.language,
            visibility = sub.visibility
        )

        repo.markApproved(submissionId, reviewerId)

        if (sub.submitterId.isNotBlank()) {
            NotificationService.sendSubmissionStatus(sub.submitterId, "approved", safeTitle)
        }
        return newId
    }

    /** Admin red: repository + bildirim */
    suspend fun reject(submissionId: String, reviewerId: String, reason: String) {
        val sub = repo.getById(submissionId) ?: error("Submission not found")
        repo.markRejected(submissionId, reviewerId, reason)
        if (sub.submitterId.isNotBlank()) {
            val safeTitle = TextSanitizer.sanitizeTitle(sub.title)
            NotificationService.sendSubmissionStatus(sub.submitterId, "rejected", safeTitle)
        }
    }
}
