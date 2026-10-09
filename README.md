# djsuwkdisowk

A professional video-download library for Android. Kotlin-first API
(builder DSL + Coroutines Flow + sealed results), modular, and fully
tested: 143 JVM tests plus a live-internet E2E harness that verifies
real downloads, real network cuts and byte-exact resumes.

## Features

- **Three transports, one API**: `DIRECT` (chunkable file, ETag/If-Range),
  `HLS` (master + media playlists, AES-128, maxHeight variant picking),
  `DASH` (MPD, audio + video selection, fMP4 muxing into one file).
- **Resume that is byte-exact by design**: a unit ledger sidecar
  (`*.vdl-units`) records every completed byte range; a task restarted
  after a network cut, app kill or reboot skips the units already on
  disk instead of re-downloading them.
- **Typed errors, no surprises**: every failure surfaces as a sealed
  `DownloadError` (`Network`, `Http`, `Storage`, `InvalidRequest`,
  `FileAlreadyExists`, `InsufficientSpace`) — including transport
  resets/EOF, which are retried inside the policy budget exactly like
  429/5xx.
- **Flexible destinations**: public Downloads, app-private dir, Gallery
  (MediaStore, atomic `IS_PENDING` on API 29+), or a user-picked SAF tree.
- **System integration**: foreground service + WorkManager glue, failure
  notifications with a Retry action, parallel-download limits,
  priority queue.
- **Root-level observability**: structured `[VDL][MODULE][component]`
  logging with lazy messages, monotonic durations and explicit
  decisions (`decision=retry`, `decision=fatal`) — greppable evidence
  for every state transition.

## Requirements

- Android `minSdk 24`, `compileSdk 36`
- JDK 17 to build
- OkHttp 5, Room, WorkManager, quickjs-kt (extractor)

## Quick start

```kotlin
// once, e.g. Application.onCreate()
VdlDownloader.initialize(context)

// 1) resolve a page or direct URL to the concrete media source
val source = when (val r = VdlDownloader.resolve(pageUrl)) {
    is ResolveOutcome.Success -> r.source   // url + kind (DIRECT/HLS/DASH)
    is ResolveOutcome.Fatal   -> return      // r.error is a typed DownloadError
}

// 2) enqueue via the DSL
val id = VdlDownloader.download {
    url = source.url
    kind = source.kind        // DIRECT | HLS | DASH
    fileName = "episode-01.mp4"
    maxHeight = 720           // HLS/DASH variant picking, null = best
    destination = Destination.Gallery(subfolder = "MyApp")
    wifiOnly = true
    showNotification = true
    retryPolicy = RetryPolicy.Exponential(baseDelayMs = 1_000, maxAttempts = 5)
}

// 3) observe as a Flow of sealed states
lifecycleScope.launch {
    VdlDownloader.observe(id).collect { state ->
        when (state) {
            is DownloadState.Downloading -> render(state.progress) // bytes, speed, ETA
            is DownloadState.Completed   -> play(state.uri)       // content:// or file path
            is DownloadState.Failed       -> show(state.error)     // sealed DownloadError
            DownloadState.Queued, DownloadState.Paused,
            DownloadState.Cancelled       -> /* ... */
        }
    }
}

// full control
VdlDownloader.pause(id); VdlDownloader.resume(id); VdlDownloader.cancel(id)
VdlDownloader.observeAll()            // Flow<List<TaskSnapshot>> for a list UI
VdlDownloader.clearCompleted()
```

`DownloadState` is a sealed interface; the compiler forces your UI to
handle every case. `Progress` carries `bytesDownloaded`, `bytesTotal`,
`speedBps` and `etaMs` — all computed monotonic-clock-safe.

## Retry & failure semantics

`RetryPolicy.Exponential` (default: 1s base, 5 attempts, 30s cap,
jittered) applies uniformly to:

- HTTP `429` / `5xx` (with `Retry-After` honored),
- transport failures — connection reset, EOF mid-body, connect/read
  timeouts — which consume attempts with backoff instead of crashing the
  engine.

When the budget is exhausted the task lands in `FAILED` with a typed
`DownloadError.Network` and the queue stays available: `resume(id)`
re-enqueues it, resuming from the unit ledger. Progress is persisted to
Room every ~2s and on every state transition, so a mid-flight process
death never loses more than one in-flight unit.

## Architecture

Single Gradle module (`:core`) with strict internal seams — the public
API is `io.vdl.core`, everything else is `internal`:

```
core/src/main/kotlin/io/vdl/core/
├── VdlDownloader.kt        # public facade (initialize/resolve/download/observe)
├── DownloadModels.kt       # DownloadState, Progress, DownloadError, TaskSnapshot
├── DownloadRequest.kt      # builder DSL, DownloadKind, Destination, RetryPolicy
├── ResolveModels.kt        # ResolveOutcome, ResolvedSource
└── internal/
    ├── engine/   # ChunkedHttpEngine, RetryFetch, Backoff
    ├── hls/      # playlist + AES-128 + unit ledger resume
    ├── dash/     # MPD parsing, audio/video, Fmp4Muxer
    ├── extract/  # QuickJS-driven source extraction
    ├── queue/    # QueueManager (events, attempts, persistence)
    ├── storage/  # destinations, atomic publish, SAF
    ├── notify/   # failure notifications with Retry
    ├── service/  # foreground service
    └── work/     # WorkManager glue
```

## Build & test

```
./gradlew :core:assembleRelease
./gradlew :core:testReleaseUnitTest
```

CI (GitHub Actions, Linux) runs the full suite on every push.

## Installation

Not on Maven Central. Publish locally and depend on it:

```
./gradlew :core:publishReleasePublicationToMavenLocal
```

```kotlin
implementation("io.vdl:core:0.1.0")
```

## License

MIT — see [LICENSE](LICENSE).
