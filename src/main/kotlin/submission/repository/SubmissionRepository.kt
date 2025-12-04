package com.example.submission.repository

import com.example.submission.model.Submission

interface SubmissionRepository {
    suspend fun createDraft(uid: String, model: Submission): String
    suspend fun updateDraft(uid: String, id: String, patch: Map<String, Any?>)
    suspend fun submit(uid: String, id: String)

    suspend fun listMine(uid: String): List<Submission>
    suspend fun listByStatus(status: String, limit: Int): List<Submission>

    suspend fun getById(id: String): Submission?
    suspend fun markApproved(id: String, reviewerId: String)
    suspend fun markRejected(id: String, reviewerId: String, reason: String)

    /** ⬅️ Yeni: Admin inline patch (status dışı alanlar) */
    suspend fun adminPatch(id: String, patch: Map<String, Any?>)
}
