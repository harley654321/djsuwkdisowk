package io.vdl.core.internal.engine

import io.vdl.core.RetryPolicy
import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with +/-20% jitter, capped by the policy max and
 * seeded by the server's Retry-After when present (Retry-After wins if larger).
 */
internal object Backoff {

    internal fun delayMs(
        policy: RetryPolicy,
        attempt: Int,
        retryAfterMs: Long? = null,
        random: Random = Random.Default
    ): Long {
        if (policy.maxAttempts <= 1) return 0L
        val exp: RetryPolicy.Exponential =
            policy as? RetryPolicy.Exponential
                ?: RetryPolicy.Exponential(baseDelayMs = policy.baseDelayMs, maxAttempts = policy.maxAttempts)
        val shift = attempt.coerceIn(0, 30)
        val raw = exp.baseDelayMs.toDouble() * (1L shl shift)
        val capped = min(raw, exp.maxDelayMs.toDouble())
        val jittered = capped * (0.8 + 0.4 * random.nextDouble())
        val base = jittered.toLong().coerceAtLeast(0L)
        return if (retryAfterMs != null) maxOf(base, retryAfterMs) else base
    }

    internal fun retryAfterMs(header: String?, nowEpochMs: Long = System.currentTimeMillis()): Long? {
        if (header.isNullOrBlank()) return null
        val h = header.trim()
        h.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        // RFC 1123 HTTP-date, e.g. "Wed, 21 Oct 2099 07:28:00 GMT".
        // SimpleDateFormat (API 1) avoids java.time's minSdk-26 requirement.
        return runCatching {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
            val at = fmt.parse(h)?.time ?: return null
            at - nowEpochMs
        }.getOrNull()?.coerceAtLeast(0L)
    }
}
