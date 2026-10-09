# Changelog

## 0.1.0 — 2026-10-09

First release: professional video-download library for Android.

### Core
- Three transports behind one API: `DIRECT` (chunked, ETag/If-Range),
  `HLS` (master/media playlists, AES-128, maxHeight variant picking),
  `DASH` (MPD, audio+video selection, fMP4 mux).
- Unit-ledger resume: `*.vdl-units` sidecar skips byte ranges already
  on disk; byte-exact recovery after network cuts, pauses and kills.
- Queue manager with priorities, parallel limits, attempts and Room
  persistence; foreground service, WorkManager glue, failure
  notifications with a Retry action.
- Destinations: public Downloads, app-private, Gallery via MediaStore
  (atomic `IS_PENDING` on API 29+), SAF tree.
- QuickJS-driven extraction to resolve page URLs into media sources.

### Hardening from live-internet E2E
- Extractor: QuickJS bindings fixed for real-world pages
  (`3185c70`), `SourceKind` promoted to top level (`feb953d`).
- DASH: representation-level codecs considered for audio selection
  (real `MultiRate.mpd` picked audio only after this fix).
- `RetryFetch`: transport failures (connection reset, EOF mid-body,
  connect/read timeout) now consume retry attempts with backoff like
  429/5xx instead of crashing the engine as
  "unexpected engine failure" (`988fd9b`). `CancellationException`
  is rethrown.

### Verification
- 143 JVM unit/integration tests (MockWebServer socket-level
  disconnects, throttle, If-Range, ledger resumes).
- Live-internet E2E harness: 8/8 scenarios — real extractions, real
  downloads, mid-flight network cut through a controlled proxy with
  byte-exact DASH (290MB) and HLS (49.5MB) resumes, pause/resume,
  unreachable-port typed failure.
- GitHub Actions CI green on Linux runners.
