// path: src/main/kotlin/com/example/rate/RateLimiter.kt
package com.example.rate

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Basit in-memory sabit pencereli rate limiter.
 * - UID başına: default 10 rpm
 * - IP başına : default 30 rpm
 *
 * Not: Çoklu instance için dağıtık bir store gerekir (Redis vb.).
 */
object RateLimiter {
    private const val WINDOW_MS = 60_000L

    private data class Bucket(var windowStartMs: Long, var count: Int)

    private val uidBuckets = ConcurrentHashMap<String, Bucket>()
    private val ipBuckets  = ConcurrentHashMap<String, Bucket>()

    private fun nowMs() = System.currentTimeMillis()
    private fun windowStart(now: Long) = (now / WINDOW_MS) * WINDOW_MS

    private fun allow(
        key: String,
        limit: Int,
        map: ConcurrentHashMap<String, Bucket>
    ): Pair<Boolean, Long> {
        val n = nowMs()
        val start = windowStart(n)
        val b = map.compute(key) { _, cur ->
            when {
                cur == null -> Bucket(start, 0)
                n - cur.windowStartMs >= WINDOW_MS -> cur.apply { windowStartMs = start; count = 0 }
                else -> cur
            }
        }!!

        synchronized(b) {
            if (n - b.windowStartMs >= WINDOW_MS) {
                b.windowStartMs = start
                b.count = 0
            }
            return if (b.count < limit) {
                b.count += 1
                true to 0L
            } else {
                val retrySec = ((b.windowStartMs + WINDOW_MS - n) / 1000L).coerceAtLeast(1L)
                false to retrySec
            }
        }
    }

    fun allowUid(uid: String, limitPerMin: Int = 10): Pair<Boolean, Long> =
        allow("uid:$uid", limitPerMin, uidBuckets)

    fun allowIp(ip: String, limitPerMin: Int = 30): Pair<Boolean, Long> =
        allow("ip:$ip", limitPerMin, ipBuckets)
}

/** Proxy arkasında da çalışacak şekilde istemci IP’si. */
fun ApplicationCall.clientIp(): String {
    val h = request.headers

    // 1) X-Forwarded-For: "client, proxy1, proxy2"
    val xff = h["X-Forwarded-For"]
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    // 2) Öne çıkan diğer header’lar
    val cf  = h["CF-Connecting-IP"]?.trim()?.takeIf { it.isNotBlank() }
    val xri = h["X-Real-IP"]?.trim()?.takeIf { it.isNotBlank() }
    val tci = h["True-Client-IP"]?.trim()?.takeIf { it.isNotBlank() }
    val xcc = h["X-Cluster-Client-Ip"]?.trim()?.takeIf { it.isNotBlank() }

    // 3) Forwarded: for=1.2.3.4;proto=https;host=...
    val fwd = h["Forwarded"]?.let(::parseForwardedFor)

    return xff ?: cf ?: xri ?: tci ?: xcc ?: fwd ?: "unknown"
}

/** RFC 7239 Forwarded header’ından 'for' alanını çek. */
private fun parseForwardedFor(header: String): String? {
    // Çoklu değer olabilir; ilk "for=" alanını çekiyoruz.
    // Örnekler: for=1.2.3.4;proto=https;host=..., for="[2001:db8:cafe::17]"
    val regex = Regex("""(?i)\bfor=\s*("?)(\[?[A-Za-z0-9:.\-]*\]?)\1""")
    return regex.find(header)?.groupValues?.getOrNull(2)?.trim()?.ifBlank { null }
}

/** 429 yardımcı. */
suspend fun ApplicationCall.respondRateLimited(
    retryAfterSeconds: Long,
    msg: String = "Too Many Requests"
) {
    response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    respond(HttpStatusCode.TooManyRequests, msg)
}
