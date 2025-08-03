package com.example.dilemma.repository

import com.example.dilemma.model.Dilemma
import com.google.cloud.firestore.Firestore
import kotlinx.coroutines.guava.await

class FirestoreDilemmaRepo(
    private val db: Firestore
) : DilemmaRepository {

    override suspend fun add(dilemma: Dilemma): String {
        val doc = db.collection("dilemmas")
            .add(dilemma.copy(id = ""))
            .get()

        doc.update("id", doc.id).get()
        return doc.id
    }

    override suspend fun getToday(): Dilemma? {
        val snap = db.collection("dilemmas")
            .orderBy("startedAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .get()

        val doc = snap.documents.firstOrNull() ?: return null
        return doc.toObject(Dilemma::class.java)?.copy(id = doc.id)
    }


    override suspend fun vote(dilemmaId: String, userId: String, choiceX: Boolean) {
        TODO("Oy kaydetme işlemi henüz yazılmadı")
    }
}