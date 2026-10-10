# Changelog

## 0.2.0 (cloudkit) — 2026-10-10

### extractor-cloudkit — 8 host families, live-verified
- `ByseExtractor` (byse.sx family): live capture proved the protocol
  evolved past upstream — the playback envelope now ships inside
  `GET /api/videos/{code}`, and key selection is version-keyed
  (`key_parts[version] + key_parts[31-version]`, 1-based) feeding an
  AES-256-GCM decrypt. Upstream's first-two-parts concat fails today's
  version 19. Resolves to a 1080p master.m3u8 the CDN serves with 200.
- `UpnshareExtractor` (uns.bio vidstack family): fragment-id ->
  `api/v1/video` hex payload -> AES-128-CBC (upstream key/IV pair) ->
  master.m3u8. Validated against the real captured ciphertext.
- `VidHideExtractor` (VidHidePro + EarnVids mirrors): player-path
  normalization (`/file|/d|/download|/f` -> `/v`) and the shared
  jwplayer/packed scanning. Host refuses sandbox connections, so the
  port ships fixture-tested with typed null degradation live.
- `Voe`, `MixDrop`, `Dood`, `StreamWish`, `Uqload` ports (already in
  0.1.x line) keep their live-validated behavior.
- New `CloudJson` shared unescape; `Byse`/`Upnshare`/`VidHide` added to
  the domain registry.
- Live suite (opt-in `CLOUDKIT_LIVE=1`) grows to 7 cases: mixdrop full
  loop (media 200), dood+streamwish+vidhide typed degradation, byse
  full loop (master 200, both real domains from the user's pages),
  upnshare decrypt.
- **CI drift-watch**: `sync-cloudkit` workflow pins the 8 upstream
  source files (sha256 manifest at
  `extractor-cloudkit/upstream-manifest.txt`, commit 4846fbac) and
  fails loudly when recloudstream/cloudstream moves them.

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
