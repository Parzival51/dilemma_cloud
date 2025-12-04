package com.example.cli

import com.example.dilemma.repository.FirestoreDilemmaRepo
import com.example.dilemma.service.DilemmaService
import com.google.cloud.firestore.SetOptions
import com.google.firebase.cloud.FirestoreClient
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset

fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty()) {
        println("⚠️  Arg lazım. Örnek: --args=\"Bugün ne içelim?; Çay, Kahve, Ayran, Hiçbiri\"")
        return@runBlocking
    }

    val raw = args.joinToString(" ").trim()
    val parts = raw.split(";")
    if (parts.size < 2) {
        println("⚠️  Biçim hatası. '<title>; opt1, opt2, opt3' şeklinde verin.")
        return@runBlocking
    }

    val title = parts[0].trim()
    val opts = parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (opts.size < 3) {
        println("⚠️  En az 3 seçenek olmalı.")
        return@runBlocking
    }

    println("⏳  \"$title\" (multi) oluşturuluyor…")

    initFirebaseAppIfNeeded()

    val db = FirestoreClient.getFirestore()
    val repo = FirestoreDilemmaRepo(db)
    val service = DilemmaService(repo)

    val id = service.createDaily(title)

    val ids = ('a'..'z').take(opts.size).map { it.toString() }
    val optionsArray = ids.zip(opts).map { (idc, label) ->
        mapOf("id" to idc, "label" to label)
    }

    db.collection("dilemmas").document(id)
        .set(
            mapOf(
                "type" to "multi",
                "options" to optionsArray,
                "optionCounts" to ids.associateWith { 0L }
            ),
            SetOptions.merge()
        ).get()

    println(
        "✅  Multi oluşturuldu → id: $id  /  tarih: ${LocalDate.now(ZoneOffset.UTC)}\n" +
                "    Seçenekler: ${optionsArray.joinToString { "${it["id"]}:${it["label"]}" }}"
    )
}
