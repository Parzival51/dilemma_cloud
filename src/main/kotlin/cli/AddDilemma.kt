package com.example.cli

import com.example.dilemma.repository.FirestoreDilemmaRepo
import com.example.dilemma.service.DilemmaService
import com.google.firebase.cloud.FirestoreClient
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset

fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty()) {
        println("⚠️  Başlık vermelisiniz:  --args=\"Kahve mi Çay mı?\"")
        return@runBlocking
    }

    val title = args.joinToString(" ")
    println("⏳  \"$title\" ikilemi ekleniyor…")

    initFirebaseAppIfNeeded()

    val db = FirestoreClient.getFirestore()
    val repo = FirestoreDilemmaRepo(db)
    val service = DilemmaService(repo)

    val id = service.createDaily(title)
    println("✅  Oluşturuldu → id: $id  /  tarih: ${LocalDate.now(ZoneOffset.UTC)}")
}
