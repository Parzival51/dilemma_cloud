package com.example.auth

import io.ktor.server.application.*

/** Opsiyonel auth senaryolarında (örn. /dilemmas/recent) UID'i güvenle okumak için. */
fun ApplicationCall.uidOrNull(): String? = attributes.getOrNull(UID_ATTR)
