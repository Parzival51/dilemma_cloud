package com.example.dilemma.routes

import com.example.dilemma.service.DilemmaService
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.response.respond

fun Route.dilemmaRoutes(service: DilemmaService) {
    get("/dilemma/today") {
        val today = service.getDaily()
        if (today == null) call.respond(HttpStatusCode.NoContent)
        else               call.respond(today)
    }
}