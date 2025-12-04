package com.example.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.*

/* -------- Ortak AttributeKey'ler -------- */
internal val UID_ATTR      = AttributeKey<String>("uid")
internal val IS_ADMIN_ATTR = AttributeKey<Boolean>("isAdmin")

suspend fun ApplicationCall.requireLogin(): String? {
    val uid = attributes.getOrNull(UID_ATTR)
    return if (uid != null) uid
    else {
        respond(HttpStatusCode.Unauthorized, "login required")
        null
    }
}

suspend fun ApplicationCall.requireAdmin(): String? {
    val uid = requireLogin() ?: return null           // 401’i üst fonk verdi
    val admin = attributes.getOrNull(IS_ADMIN_ATTR) == true
    return if (admin) uid
    else {
        respond(HttpStatusCode.Forbidden, "admin only")
        null
    }
}

val ApplicationCall.uid: String get() = attributes[UID_ATTR]
val ApplicationCall.isAdmin: Boolean get() = attributes[IS_ADMIN_ATTR] == true
