package com.example.submission.routes

import com.example.auth.requireAdmin
import com.example.auth.requireLogin
import com.example.rate.RateLimiter
import com.example.rate.clientIp
import com.example.rate.respondRateLimited
import com.example.submission.service.SubmissionService
import com.example.util.TextSanitizer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class DraftBody(
    val title: String,
    val xLabel: String? = null,
    val yLabel: String? = null,
    val context: String? = null,
    val sourceUrl: String? = null,
    val topic: String? = null,
    val region: String? = null,

    // Yeni metadata alanları (opsiyonel)
    val category: String? = null,
    val questionType: String? = null,
    val language: String? = null,
    val visibility: String? = null
)

@Serializable
private data class IdRes(val id: String)

@Serializable
private data class PatchBody(
    val title: String? = null,
    val xLabel: String? = null,
    val yLabel: String? = null,
    val context: String? = null,
    val sourceUrl: String? = null,
    val topic: String? = null,
    val region: String? = null,

    val category: String? = null,
    val questionType: String? = null,
    val language: String? = null,
    val visibility: String? = null
)

@Serializable
private data class RejectBody(val reason: String)

/** Admin inline edit için – sadece belirli alanlar */
@Serializable
private data class AdminPatchBody(
    val title: String? = null,
    val xLabel: String? = null,
    val yLabel: String? = null,
    val context: String? = null,
    val sourceUrl: String? = null,
    val topic: String? = null,
    val region: String? = null,

    val category: String? = null,
    val questionType: String? = null,
    val language: String? = null,
    val visibility: String? = null
)

fun Route.submissionRoutes(service: SubmissionService) {

    /* ---------- User endpoints ---------- */
    route("/submissions") {

        post {
            val uid = call.requireLogin() ?: return@post

            // 🔒 Rate limit: UID 6 rpm, IP 30 rpm
            val (okU, ru) = RateLimiter.allowUid(uid, 6)
            if (!okU) return@post call.respondRateLimited(ru, "rate-limited: uid")
            val (okI, ri) = RateLimiter.allowIp(call.clientIp(), 30)
            if (!okI) return@post call.respondRateLimited(ri, "rate-limited: ip")

            val b = call.receive<DraftBody>()

            val title = TextSanitizer.sanitizeTitle(b.title)
            TextSanitizer.requireLenBetween(title, 4, 120, "title")

            val xLabel = b.xLabel?.let(TextSanitizer::sanitizeShortLabel)
            val yLabel = b.yLabel?.let(TextSanitizer::sanitizeShortLabel)
            if (xLabel != null) TextSanitizer.requireLenBetween(xLabel, 1, 40, "xLabel")
            if (yLabel != null) TextSanitizer.requireLenBetween(yLabel, 1, 40, "yLabel")

            val context = b.context?.let(TextSanitizer::sanitizeReason)
            val sourceUrl = b.sourceUrl?.let(TextSanitizer::normalize)

            // ---- Metadata normalizasyonu ----
            val category = b.category
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }

            val rawQType = b.questionType
                ?.trim()
                ?.lowercase()
            val questionType = when (rawQType) {
                "prediction", "advice", "poll" -> rawQType
                null -> null
                else -> "prediction"
            }

            val lang = b.language
                ?.trim()
                ?.lowercase()
            val language = if (!lang.isNullOrBlank()) lang.take(8) else null

            val rawVis = b.visibility
                ?.trim()
                ?.lowercase()
            val visibility = when (rawVis) {
                "public", "private", "unlisted" -> rawVis
                null -> null
                else -> "public"
            }

            val id = service.createDraft(
                uid = uid,
                title = title,
                x = xLabel,
                y = yLabel,
                ctx = context,
                src = sourceUrl,
                topic = b.topic,
                region = b.region,
                category = category,
                questionType = questionType,
                language = language,
                visibility = visibility
            )
            call.respond(HttpStatusCode.Created, IdRes(id))
        }

        get("/mine") {
            val uid = call.requireLogin() ?: return@get
            val list = service.mine(uid)
            call.respond(list)
        }

        put("/{id}") {
            val uid = call.requireLogin() ?: return@put

            // 🔒 Rate limit: UID 6 rpm, IP 30 rpm
            val (okU, ru) = RateLimiter.allowUid(uid, 6)
            if (!okU) return@put call.respondRateLimited(ru, "rate-limited: uid")
            val (okI, ri) = RateLimiter.allowIp(call.clientIp(), 30)
            if (!okI) return@put call.respondRateLimited(ri, "rate-limited: ip")

            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val p = call.receive<PatchBody>()

            val patch = buildMap<String, Any?> {
                p.title?.let {
                    val t = TextSanitizer.sanitizeTitle(it)
                    TextSanitizer.requireLenBetween(t, 4, 120, "title")
                    put("title", t)
                }
                p.xLabel?.let {
                    val x = TextSanitizer.sanitizeShortLabel(it)
                    TextSanitizer.requireLenBetween(x, 1, 40, "xLabel")
                    put("xLabel", x)
                }
                p.yLabel?.let {
                    val y = TextSanitizer.sanitizeShortLabel(it)
                    TextSanitizer.requireLenBetween(y, 1, 40, "yLabel")
                    put("yLabel", y)
                }
                p.context?.let { put("context", TextSanitizer.sanitizeReason(it)) }
                p.sourceUrl?.let { put("sourceUrl", TextSanitizer.normalize(it)) }
                p.topic?.let { put("topic", it.trim()) }
                p.region?.let { put("region", it.trim()) }

                p.category?.let {
                    val c = it.trim().lowercase()
                    if (c.isNotEmpty()) put("category", c)
                }

                p.questionType?.let {
                    val raw = it.trim().lowercase()
                    val qt = when (raw) {
                        "prediction", "advice", "poll" -> raw
                        else -> "prediction"
                    }
                    put("questionType", qt)
                }

                p.language?.let {
                    val lang = it.trim().lowercase()
                    if (lang.isNotEmpty()) put("language", lang.take(8))
                }

                p.visibility?.let {
                    val raw = it.trim().lowercase()
                    val vis = when (raw) {
                        "public", "private", "unlisted" -> raw
                        else -> "public"
                    }
                    put("visibility", vis)
                }
            }

            service.updateDraft(uid, id, patch)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/{id}/submit") {
            val uid = call.requireLogin() ?: return@post

            // 🔒 Rate limit: UID 6 rpm, IP 30 rpm
            val (okU, ru) = RateLimiter.allowUid(uid, 6)
            if (!okU) return@post call.respondRateLimited(ru, "rate-limited: uid")
            val (okI, ri) = RateLimiter.allowIp(call.clientIp(), 30)
            if (!okI) return@post call.respondRateLimited(ri, "rate-limited: ip")

            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            service.submit(uid, id)
            call.respond(HttpStatusCode.Accepted)
        }
    }

    /* ---------- Admin endpoints ---------- */
    route("/admin/submissions") {

        get {
            call.requireAdmin() ?: return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val list = service.pending(limit.coerceIn(1, 200))
            call.respond(list)
        }

        /** Admin inline edit/normalize – status değişmez. */
        post("/{id}/patch") {
            val reviewer = call.requireAdmin() ?: return@post

            // 🔒 Rate limit: admin UID 10 rpm, IP 30 rpm
            val (okU, ru) = RateLimiter.allowUid(reviewer, 10)
            if (!okU) return@post call.respondRateLimited(ru, "rate-limited: uid")
            val (okI, ri) = RateLimiter.allowIp(call.clientIp(), 30)
            if (!okI) return@post call.respondRateLimited(ri, "rate-limited: ip")

            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val p = call.receive<AdminPatchBody>()

            val patch = buildMap<String, Any?> {
                p.title?.let {
                    val t = TextSanitizer.sanitizeTitle(it)
                    TextSanitizer.requireLenBetween(t, 4, 120, "title")
                    put("title", t)
                }
                p.xLabel?.let {
                    val x = TextSanitizer.sanitizeShortLabel(it)
                    TextSanitizer.requireLenBetween(x, 1, 40, "xLabel")
                    put("xLabel", x)
                }
                p.yLabel?.let {
                    val y = TextSanitizer.sanitizeShortLabel(it)
                    TextSanitizer.requireLenBetween(y, 1, 40, "yLabel")
                    put("yLabel", y)
                }
                p.context?.let { put("context", TextSanitizer.sanitizeReason(it)) }
                p.sourceUrl?.let { put("sourceUrl", TextSanitizer.normalize(it)) }
                p.topic?.let { put("topic", it.trim()) }
                p.region?.let { put("region", it.trim()) }

                p.category?.let {
                    val c = it.trim().lowercase()
                    if (c.isNotEmpty()) put("category", c)
                }

                p.questionType?.let {
                    val raw = it.trim().lowercase()
                    val qt = when (raw) {
                        "prediction", "advice", "poll" -> raw
                        else -> "prediction"
                    }
                    put("questionType", qt)
                }

                p.language?.let {
                    val lang = it.trim().lowercase()
                    if (lang.isNotEmpty()) put("language", lang.take(8))
                }

                p.visibility?.let {
                    val raw = it.trim().lowercase()
                    val vis = when (raw) {
                        "public", "private", "unlisted" -> raw
                        else -> "public"
                    }
                    put("visibility", vis)
                }
            }

            service.adminPatch(id, patch)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/{id}/approve") {
            val reviewer = call.requireAdmin() ?: return@post

            // 🔒 Rate limit: admin UID 10 rpm, IP 30 rpm
            val (okU, ru) = RateLimiter.allowUid(reviewer, 10)
            if (!okU) return@post call.respondRateLimited(ru, "rate-limited: uid")
            val (okI, ri) = RateLimiter.allowIp(call.clientIp(), 30)
            if (!okI) return@post call.respondRateLimited(ri, "rate-limited: ip")

            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val publishedId = service.approve(id, reviewer)
            call.respond(HttpStatusCode.Created, IdRes(publishedId))
        }

        post("/{id}/reject") {
            val reviewer = call.requireAdmin() ?: return@post

            // 🔒 Rate limit: admin UID 10 rpm, IP 30 rpm
            val (okU, ru) = RateLimiter.allowUid(reviewer, 10)
            if (!okU) return@post call.respondRateLimited(ru, "rate-limited: uid")
            val (okI, ri) = RateLimiter.allowIp(call.clientIp(), 30)
            if (!okI) return@post call.respondRateLimited(ri, "rate-limited: ip")

            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<RejectBody>()
            val clean = TextSanitizer.sanitizeReason(body.reason)
            service.reject(id, reviewer, clean.ifBlank { "unspecified" })
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
