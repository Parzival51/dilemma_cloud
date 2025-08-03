package com.example.dilemma.routes

import com.example.dilemma.service.DilemmaService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable


/** ------------  veri modelleri  ------------ **/

@Serializable
private data class NewDilemmaBody(val title: String)

@Serializable
private data class IdResponse(val id: String)


fun Route.adminRoutes(service: DilemmaService) = route("/admin") {

    post("/dilemma") {
        val body = call.receive<NewDilemmaBody>()
        val id   = service.createDaily(body.title)
        call.respond(HttpStatusCode.Created, IdResponse(id))
    }
}