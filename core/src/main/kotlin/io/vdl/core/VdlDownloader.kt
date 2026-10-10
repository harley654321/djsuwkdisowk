package io.vdl.core

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Public facade of the download library.
 *
 * Typical usage:
 * ```
 * VdlDownloader.initialize(context)
 * val id = VdlDownloader.download {
 *     url = "https://example.com/video.mp4"
 *     fileName = "video.mp4"
 *     destination = Destination.PublicDownloads("MyApp")
 * }
 * VdlDownloader.observe(id).collect { state -> ... }
 * ```
 */
public object VdlDownloader {

    internal val bridge: VdlBridge = VdlBridge()

    /** Idempotent; call once from Application.onCreate(). */
    @JvmOverloads
    public fun initialize(context: Context, config: VdlConfig = VdlConfig()): Boolean =
        bridge.initialize(context.applicationContext, config)

    /**
     * Resolve a page URL (or direct media URL) into a concrete media source
     * with its transport kind, via [ResolveOutcome]. The resolved url is
     * ready for [download].
     */
    public suspend fun resolve(url: String): ResolveOutcome = bridge.require().resolve(url)

    /**
     * Enqueue a download. Returns its id, or throws [DuplicateDownloadException]
     * when an identical url+fileName task already exists.
     */
    public suspend fun download(block: DownloadRequestBuilder.() -> Unit): String =
        bridge.require().submit(DownloadRequestBuilder().apply(block).build())

    public fun observe(id: String): Flow<DownloadState> = bridge.require().observe(id)

    public fun observeAll(): Flow<List<TaskSnapshot>> = bridge.require().observeAll()

    public fun pause(id: String) { bridge.require().pause(id) }

    /** Clears any system pause (e.g. after a dataSync timeout) for this task. */
    public fun resume(id: String) { bridge.require().resume(id) }

    public fun cancel(id: String) { bridge.require().cancel(id) }

    public fun cancelAll() { bridge.require().cancelAll() }

    public suspend fun clearCompleted(): Int = bridge.require().clearCompleted()

    public suspend fun getTask(id: String): TaskSnapshot? = bridge.require().getTask(id)

    /**
     * Pauses running tasks (synchronously persisted as PAUSED), stops the loop
     * and closes the database. Suspend since v0.2: guarantees no task is left
     * RUNNING in the DB after app teardown (StressLab Q10 contract).
     */
    public suspend fun shutdown() { bridge.engine?.shutdown() }
}
