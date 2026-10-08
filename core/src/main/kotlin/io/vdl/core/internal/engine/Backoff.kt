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
        return header.trim().toLongOrNull()?.let { nowEpochMs + it.coerceAtLeast(0L) - nowEpochMs }
            ?: runCatching {
                java.time.Instant.parse(header.trim()).toEpochMilli() - nowEpochMs
            }.getOrNull()?.coerceAtLeast(0L)
    }
}
