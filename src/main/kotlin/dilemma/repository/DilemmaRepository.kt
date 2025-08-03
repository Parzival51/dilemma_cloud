package com.example.dilemma.repository

import com.example.dilemma.model.Dilemma

interface DilemmaRepository {
    suspend fun add(dilemma: Dilemma): String
    suspend fun getToday(): Dilemma?
    suspend fun vote(dilemmaId: String, userId: String, choiceX: Boolean)
}