package com.example.dilemma.repository

import com.example.dilemma.model.Dilemma
import com.example.dilemma.model.DilemmaStats

interface DilemmaRepository {
    suspend fun add(dilemma: Dilemma): String
    suspend fun getToday(): Dilemma?
    suspend fun vote(dilemmaId: String, userId: String, choiceX: Boolean)
    suspend fun getStats(dilemmaId: String): DilemmaStats?
    suspend fun getRecent(limit: Int): List<Dilemma>

}