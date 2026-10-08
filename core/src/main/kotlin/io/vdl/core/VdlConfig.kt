package io.vdl.core

import android.content.Context

/**
 * Pluggable logging sink. On Android the default sink writes to logcat
 * with structured tags; tests can capture everything.
 */
public interface Logger {
    public enum class Level { DEBUG, INFO, WARN, ERROR }

    public fun isLoggable(level: Level): Boolean = true

    public fun log(level: Level, tag: String, message: String, error: Throwable? = null)
}

/** Library configuration, passed to [VdlDownloader.initialize]. */
public class VdlConfig public constructor(
    public val maxParallelDownloads: Int = 3,
    public val progressIntervalMs: Long = 500L,
    public val debugLogging: Boolean = false,
    public val logger: Logger? = null
) {
    init {
        require(maxParallelDownloads in 1..16) { "maxParallelDownloads must be in 1..16" }
        require(progressIntervalMs in 50..10_000) { "progressIntervalMs must be in 50..10000" }
    }
}

/** Self-contained bootstrap state so misuse fails loudly. */
internal object InitGuard {
    internal lateinit var appContext: Context
    internal var initialized: Boolean = false

    internal fun requireInitialized() {
        check(initialized) { "VdlDownloader.initialize(context) was not called" }
    }
}
