// file: src/main/kotlin/com/example/submission/repository/FirestoreSubmissionRepo.kt
package com.example.submission.repository

import com.example.submission.model.Submission
import com.google.cloud.firestore.SetOptions
import com.google.firebase.cloud.FirestoreClient
import java.time.Instant

class FirestoreSubmissionRepo : SubmissionRepository {
    private val col = FirestoreClient.getFirestore().collection("dilemma_submissions")

    override suspend fun createDraft(uid: String, model: Submission): String {
        val data = hashMapOf<String, Any?>(
            "title" to model.title,
            "xLabel" to model.xLabel,
            "yLabel" to model.yLabel,
            "context" to model.context,
            "sourceUrl" to model.sourceUrl,
            "status" to "draft",
            "submitterId" to uid,
            "createdAt" to Instant.now().toEpochMilli(),
            "topic" to model.topic,
            "region" to model.region,

            // 🔹 Yeni metadata alanları
            "category" to model.category,
            "questionType" to model.questionType,
            "language" to model.language,
            "visibility" to model.visibility
        )
        val ref = col.add(data).get()
        return ref.id
    }

    override suspend fun updateDraft(uid: String, id: String, patch: Map<String, Any?>) {
        val doc = col.document(id).get().get()
        if (!doc.exists()) error("Submission not found")
        val owner = doc.getString("submitterId")
        val status = doc.getString("status") ?: "draft"
        require(owner == uid) { "Forbidden" }
        require(status == "draft") { "Only draft can be updated" }

        // 🔒 Kullanıcı patch’lerinde sadece içerik + metadata alanlarına izin ver
        val allowed = setOf(
            "title", "xLabel", "yLabel", "context", "sourceUrl",
            "topic", "region",
            "category", "questionType", "language", "visibility"
        )
        val clean = patch.filterKeys { it in allowed }
        if (clean.isEmpty()) return

        col.document(id).set(clean, SetOptions.merge()).get()
    }

    override suspend fun submit(uid: String, id: String) {
        val doc = col.document(id).get().get()
        if (!doc.exists()) error("Submission not found")
        val owner = doc.getString("submitterId")
        val status = doc.getString("status") ?: "draft"
        require(owner == uid) { "Forbidden" }
        require(status == "draft") { "Only draft can be submitted" }
        col.document(id).set(
            mapOf("status" to "pending", "submittedAt" to Instant.now().toEpochMilli()),
            SetOptions.merge()
        ).get()
    }

    override suspend fun listMine(uid: String): List<Submission> {
        val snap = col.whereEqualTo("submitterId", uid)
            .orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
            .limit(200).get().get()
        return snap.documents.map { toModel(it.id, it.data ?: emptyMap()) }
    }

    override suspend fun listByStatus(status: String, limit: Int): List<Submission> {
        val snap = col.whereEqualTo("status", status)
            .orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
            .limit(limit).get().get()
        return snap.documents.map { toModel(it.id, it.data ?: emptyMap()) }
    }

    override suspend fun getById(id: String): Submission? {
        val d = col.document(id).get().get()
        if (!d.exists()) return null
        return toModel(d.id, d.data ?: emptyMap())
    }

    override suspend fun markApproved(id: String, reviewerId: String) {
        col.document(id).set(
            mapOf(
                "status" to "approved",
                "reviewedAt" to Instant.now().toEpochMilli(),
                "reviewerId" to reviewerId,
                "rejectReason" to null
            ),
            SetOptions.merge()
        ).get()
    }

    override suspend fun markRejected(id: String, reviewerId: String, reason: String) {
        col.document(id).set(
            mapOf(
                "status" to "rejected",
                "reviewedAt" to Instant.now().toEpochMilli(),
                "reviewerId" to reviewerId,
                "rejectReason" to reason
            ),
            SetOptions.merge()
        ).get()
    }

    override suspend fun adminPatch(id: String, patch: Map<String, Any?>) {
        // 🔒 Admin patch: status hariç her şeyi düzenleyebilsin
        val allowed = setOf(
            "title", "xLabel", "yLabel", "context", "sourceUrl",
            "topic", "region",
            "category", "questionType", "language", "visibility"
        )
        val clean = patch.filterKeys { it in allowed }
        if (clean.isEmpty()) return

        col.document(id).set(clean, SetOptions.merge()).get()
    }

    private fun toModel(id: String, m: Map<String, Any>): Submission {
        fun <T> g(key: String): T? = m[key] as? T
        return Submission(
            id = id,
            title = g<String>("title") ?: "",
            xLabel = g("xLabel"),
            yLabel = g("yLabel"),
            context = g("context"),
            sourceUrl = g("sourceUrl"),
            status = g("status") ?: "draft",
            submitterId = g("submitterId") ?: "",
            createdAt = (g<Long>("createdAt") ?: 0L),
            submittedAt = g("submittedAt"),
            reviewedAt = g("reviewedAt"),
            reviewerId = g("reviewerId"),
            rejectReason = g("rejectReason"),
            topic = g("topic"),
            region = g("region"),

            // 🔹 Yeni metadata alanları model’e de geri okunuyor
            category = g("category"),
            questionType = g("questionType"),
            language = g("language"),
            visibility = g("visibility")
        )
    }
}
