package io.vdl.core.internal.engine

import java.io.IOException

/** Sentinel: the remote resource changed under us; wipe and restart once. */
internal object RestartSignal {
    override fun toString(): String = "RestartSignal"
}

internal sealed interface TransferResult {
    internal data object Ok : TransferResult
    internal object Restart : TransferResult
    internal data class Fail(internal val outcome: EngineOutcome) : TransferResult
}

/** Retryable HTTP condition (429/5xx) that may carry Retry-After. */
internal class Throttle internal constructor(
    message: String,
    internal val retryAfterMs: Long?
) : IOException(message)
