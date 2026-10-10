# djsuwkdisowk

[English](README.md) | **Español**

Una librería profesional de descarga de videos para Android. API
Kotlin-first (builder DSL + Coroutines Flow + resultados sellados),
modular y totalmente probada: 143 tests JVM más un harness E2E con
internet real que verifica descargas reales, cortes de red reales y
reanudaciones byte-exactas.

## Características

- **Tres transportes, una sola API**: `DIRECT` (archivo troceable,
  ETag/If-Range), `HLS` (playlists master + media, AES-128, selección
  de variante por maxHeight), `DASH` (MPD, selección de audio + video,
  mux fMP4 en un solo archivo).
- **Reanudación byte-exacta por diseño**: un ledger de unidades
  (`*.vdl-units`) registra cada rango de bytes completado; una tarea
  reiniciada tras un corte de red, cierre forzado o reboot salta las
  unidades ya en disco en lugar de re-descargarlas.
- **Errores tipados, sin sorpresas**: todo fallo se reporta como un
  `DownloadError` sellado (`Network`, `Http`, `Storage`,
  `InvalidRequest`, `FileAlreadyExists`, `InsufficientSpace`) —
  incluidos resets/EOF de transporte, que se reintentan dentro del
  presupuesto de la política exactamente igual que 429/5xx.
- **Destinos flexibles**: Descargas públicas, directorio privado de la
  app, Galería (MediaStore, `IS_PENDING` atómico en API 29+), o un árbol
  SAF elegido por el usuario.
- **Integración con el sistema**: servicio en primer plano + pegamento
  WorkManager, notificaciones de fallo con acción Reintentar, límites
  de descargas paralelas, cola con prioridades.
- **Observabilidad a nivel raíz**: logging estructurado
  `[VDL][MODULO][componente]` con mensajes lazy, duraciones monótonas y
  decisiones explícitas (`decision=retry`, `decision=fatal`) — evidencia
  greppable de cada transición de estado.
- **Extractores de hosts como módulo opcional**: `extractor-cloudkit`
  (GPL-3.0, aislado del `:core` MIT) resuelve páginas embed de los
  principales hosts de video — voe, mixdrop, dood, streamwish, uqload,
  byse, vidhide, upnshare — a URLs de medios listas para descargar,
  con referer y tipo HLS/DIRECT tipado. Cada extractor trae su test de
  fixture construido desde una captura real, y un drift-watch de CI
  fija los archivos upstream de los que derivan los ports.

## Requisitos

- Android `minSdk 24`, `compileSdk 36`
- JDK 17 para compilar
- OkHttp 5, Room, WorkManager, quickjs-kt (extractor)

## Inicio rápido

```kotlin
// una vez, p. ej. Application.onCreate()
VdlDownloader.initialize(context)

// 1) resolver una página o URL directa a la fuente de medios concreta
val source = when (val r = VdlDownloader.resolve(pageUrl)) {
    is ResolveOutcome.Success -> r.source   // url + kind (DIRECT/HLS/DASH)
    is ResolveOutcome.Fatal   -> return      // r.error es un DownloadError tipado
}

// 2) encolar con el DSL
val id = VdlDownloader.download {
    url = source.url
    kind = source.kind        // DIRECT | HLS | DASH
    fileName = "episodio-01.mp4"
    maxHeight = 720           // selección de variante HLS/DASH, null = la mejor
    destination = Destination.Gallery(subfolder = "MiApp")
    wifiOnly = true
    showNotification = true
    retryPolicy = RetryPolicy.Exponential(baseDelayMs = 1_000, maxAttempts = 5)
}

// 3) observar como Flow de estados sellados
lifecycleScope.launch {
    VdlDownloader.observe(id).collect { state ->
        when (state) {
            is DownloadState.Downloading -> render(state.progress) // bytes, velocidad, ETA
            is DownloadState.Completed   -> play(state.uri)       // content:// o ruta de archivo
            is DownloadState.Failed       -> show(state.error)     // DownloadError sellado
            DownloadState.Queued, DownloadState.Paused,
            DownloadState.Cancelled       -> /* ... */
        }
    }
}

// control total
VdlDownloader.pause(id); VdlDownloader.resume(id); VdlDownloader.cancel(id)
VdlDownloader.observeAll()            // Flow<List<TaskSnapshot>> para una UI de lista
VdlDownloader.clearCompleted()
```

`DownloadState` es una interfaz sellada; el compilador obliga a tu UI a
manejar todos los casos. `Progress` lleva `bytesDownloaded`,
`bytesTotal`, `speedBps` y `etaMs` — todos calculados con seguridad de
reloj monótono.

## Semántica de reintentos y fallos

`RetryPolicy.Exponential` (por defecto: base 1s, 5 intentos, tope 30s,
con jitter) se aplica uniformemente a:

- HTTP `429` / `5xx` (respetando `Retry-After`),
- fallos de transporte — reset de conexión, EOF a mitad del body,
  timeouts de conexión/lectura — que consumen intentos con backoff en
  lugar de tumbar el engine.

Cuando se agota el presupuesto, la tarea pasa a `FAILED` con un
`DownloadError.Network` tipado y la cola sigue disponible:
`resume(id)` la re-encola reanudando desde el ledger de unidades. El
progreso se persiste en Room cada ~2s y en cada transición de estado,
así que la muerte del proceso en pleno vuelo nunca pierde más de una
unidad en curso.

## Arquitectura

Dos módulos Gradle. `:core` mantiene costuras internas estrictas — la
API pública es `io.vdl.core`, todo lo demás es `internal`.
`:extractor-cloudkit` (GPL-3.0) es un módulo separado para que su código
derivado de upstream nunca se mezcle con el core MIT:

```
core/src/main/kotlin/io/vdl/core/
├── VdlDownloader.kt        # fachada pública (initialize/resolve/download/observe)
├── DownloadModels.kt       # DownloadState, Progress, DownloadError, TaskSnapshot
├── DownloadRequest.kt      # builder DSL, DownloadKind, Destination, RetryPolicy
├── ResolveModels.kt        # ResolveOutcome, ResolvedSource
└── internal/
    ├── engine/   # ChunkedHttpEngine, RetryFetch, Backoff
    ├── hls/      # playlists + AES-128 + reanudación por ledger de unidades
    ├── dash/     # parsing MPD, audio/video, Fmp4Muxer
    ├── extract/  # extracción de fuentes con QuickJS
    ├── queue/    # QueueManager (eventos, intentos, persistencia)
    ├── storage/  # destinos, publicación atómica, SAF
    ├── notify/   # notificaciones de fallo con Reintentar
    ├── service/  # servicio en primer plano
    └── work/     # pegamento WorkManager
```

```
extractor-cloudkit/src/main/kotlin/io/vdl/cloudkit/
├── CloudKit.kt              # público: resolve(url, client) -> CloudKitResult?
└── internal/                # un extractor por familia de hosts (ports GPL-3.0)
    ├── ByseExtractor.kt        # envelope de api -> AES-256-GCM con clave por versión
    ├── UpnshareExtractor.kt   # payload hex de uns.bio -> AES-128-CBC
    ├── VidHideExtractor.kt    # páginas player jwplayer/packed
    └── Voe/MixDrop/Dood/StreamWish/Uqload …
```

Resolución opcional de hosts, una sola llamada:

```kotlin
val media = CloudKit.resolve(embedUrl, okHttpClient)
// media?.url / media?.kind (HLS|DIRECT) / media?.referer / media?.extractor
```

## Compilar y probar

```
./gradlew :core:assembleRelease
./gradlew :core:testReleaseUnitTest
./gradlew :extractor-cloudkit:testDebugUnitTest
```

CI (GitHub Actions, Linux) corre la suite completa en cada push, más el
drift-watch semanal `sync-cloudkit` sobre los archivos upstream de los
que derivan los extractores GPL.

## Instalación

No está en Maven Central. Publica localmente y depende de ella:

```
./gradlew :core:publishReleasePublicationToMavenLocal
```

```kotlin
implementation("io.vdl:core:0.1.0")
```

## Licencia

MIT — ver [LICENSE](LICENSE). El módulo `extractor-cloudkit` es GPL-3.0
por derivar de ports upstream; ver
[extractor-cloudkit/LICENSE-NOTE.md](extractor-cloudkit/LICENSE-NOTE.md).
