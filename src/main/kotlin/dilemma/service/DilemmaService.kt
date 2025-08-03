package com.example.dilemma.service

import com.example.dilemma.model.Dilemma
import com.example.dilemma.repository.DilemmaRepository
import java.util.concurrent.TimeUnit

class DilemmaService(
    private val repo: DilemmaRepository
) {
    suspend fun createDaily(title: String): String {
        val now = System.currentTimeMillis()
        val expires = now + TimeUnit.HOURS.toMillis(48)

        val d = Dilemma(
            title      = title,
            startedAt  = now,
            expiresAt  = expires
        )
        return repo.add(d)
    }

    suspend fun getDaily(): Dilemma? =
        repo.getToday()          // repo henüz TODO; ileride çalışacak

    suspend fun vote(dilemmaId: String, userId: String, choiceX: Boolean) {
        TODO("Oy kaydetme mantığı sonra eklenecek")
    }
}