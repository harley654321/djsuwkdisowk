package io.vdl.core.internal.extract

import io.vdl.core.internal.logging.VdlLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.dokar.quickjs.QuickJs

/**
 * Seam between the extractor and a JavaScript engine. Implementations must
 * be safe for concurrent callers (serialized internally) and must never
 * throw: errors are evidence, returned as null.
 */
internal interface JsEngine {
    /** Evaluates a single expression; returns its string value, or null. */
    suspend fun evaluateString(expr: String): String?

    fun close()
}

/**
 * Real QuickJS engine (io.github.dokar3:quickjs-kt). Evaluations are
 * serialized by a mutex (the runtime is single-threaded), confine to IO
 * because native evaluation blocks, and every snippet is length-capped
 * so hostile pages cannot hand megabytes of code to the engine.
 *
 * The expression contract is enforced by [JsUrlSolver]: only whitelisted
 * character sets built by us (never raw page scripts) reach evaluate().
 */
internal class QuickJsEngine internal constructor(
    private val log: VdlLog
) : JsEngine {

    private val mutex = Mutex()
    private val runtime = QuickJs.create(Dispatchers.Default)

    override suspend fun evaluateString(expr: String): String? = mutex.withLock {
        if (expr.length > MAX_EXPR_CHARS) {
            log.w(TAG) { "js expr rejected len=${expr.length} cap=$MAX_EXPR_CHARS" }
            return@withLock null
        }
        try {
            withContext(Dispatchers.IO) {
                // evaluate<String> throws when the result is not a string
                // (dokar3 type converters): caught below, reported as null.
                runtime.evaluate<String>(expr)
            }
        } catch (t: Throwable) {
            log.e(TAG, t) { "js eval failed len=${expr.length} decision=null-result" }
            null
        }
    }

    override fun close() {
        try {
            runtime.close()
        } catch (t: Throwable) {
            log.e(TAG, t) { "js runtime close failed decision=ignore" }
        }
    }

    internal companion object {
        internal const val TAG = "[VDL][EXTRACT][JS]"
        private const val MAX_EXPR_CHARS = 16_000
    }
}
