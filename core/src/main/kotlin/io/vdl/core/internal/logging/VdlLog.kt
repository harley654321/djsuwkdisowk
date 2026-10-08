package io.vdl.core.internal.logging

import io.vdl.core.Logger
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured, lazy logging facade.
 *
 * Tag format: [VDL][MODULE][COMPONENT] plus a free-form suffix that callers
 * use for task ids, threads and sizes, e.g. "[VDL][QUEUE][dispatch] task=1c queued".
 *
 * - Messages are built lazily: the lambda never runs when the level is off.
 * - Durations come from [System.nanoTime] (monotonic), never wall clock.
 * - No catch block in this library is silent: every failure logs ERROR with
 *   the throwable and an explicit decision note.
 */
internal class VdlLog internal constructor(
    private val sink: Logger?,
    private val debug: Boolean
) {
    internal fun d(tag: String, message: () -> String) {
        if (debug && sink != null && sink.isLoggable(Logger.Level.DEBUG)) {
            sink.log(Logger.Level.DEBUG, tag, message())
        }
    }

    internal fun i(tag: String, message: () -> String) {
        if (sink != null && sink.isLoggable(Logger.Level.INFO)) {
            sink.log(Logger.Level.INFO, tag, message())
        }
    }

    internal fun w(tag: String, message: () -> String) {
        if (sink != null && sink.isLoggable(Logger.Level.WARN)) {
            sink.log(Logger.Level.WARN, tag, message())
        }
    }

    internal fun e(tag: String, error: Throwable? = null, message: () -> String) {
        if (sink != null && sink.isLoggable(Logger.Level.ERROR)) {
            sink.log(Logger.Level.ERROR, tag, message(), error)
        }
    }

    /** Measures a suspend block with a monotonic clock and logs start/success/fail. */
    internal suspend fun <T> timed(
        tag: String,
        name: String,
        slowThresholdMs: Long = 2_000L,
        block: suspend () -> T
    ): T {
        val start = System.nanoTime()
        d(tag) { "$name start" }
        return try {
            val result = block()
            val ms = elapsedMs(start)
            if (ms >= slowThresholdMs) {
                w(tag) { "$name success dt=${ms}ms SLOW (threshold=${slowThresholdMs}ms)" }
            } else {
                d(tag) { "$name success dt=${ms}ms" }
            }
            result
        } catch (t: Throwable) {
            val ms = elapsedMs(start)
            if (t is kotlinx.coroutines.CancellationException) {
                i(tag) { "$name cancelled dt=${ms}ms" }
            } else {
                e(tag, t) { "$name fail dt=${ms}ms" }
            }
            throw t
        }
    }

    private fun elapsedMs(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000L

    internal companion object {
        private val seq = AtomicLong(0)

        fun nextSeq(): Long = seq.incrementAndGet()
    }
}
