# RUNBOOK — Reconstrucción completa del entorno de pruebas

> Manual interno de reconstrucción. El sandbox local es efímero (se borra en
> cada reset); la fuente de verdad SIEMPRE es este repo. Este documento existe
> para que cualquier sesión/sandbox limpio vuelva a tener TODO funcionando
> en minutos. No está enlazado desde el README: es infraestructura nuestra.

## Qué se pierde y qué no

| Pieza | ¿Se pierde en reset? | Cómo se recupera |
|---|---|---|
| Código, tests, fixes | NO (GitHub) | `git clone` |
| JDK 17 local | SÍ | `bootstrap.sh` (~2 min, Temurin vía adoptium API) |
| Jars de dependencias (okhttp/okio/stdlib) | SÍ (caché gradle) | `bootstrap.sh` los re-descarga de Maven Central |
| classes.jar del módulo | SÍ (build/) | `./gradlew :extractor-cloudkit:assembleRelease` |
| Matriz de URLs live | Caduca sola (tokens de CDN) | `harness.py` regenera 75 URLs frescas |
| StressLab caos Q1–Q10 (original) | **PERDIDO** (se construyó fuera del repo) | Reconstrucción pendiente; los 3 fixes y sus tests SÍ están en core |

## Reconstrucción completa (sandbox limpio)

```bash
git clone https://github.com/harley654321/djsuwkdisowk && cd djsuwkdisowk
bash tools/env/bootstrap.sh          # JDK 17 + smoke completo
```

`bootstrap.sh` hace, en orden:
1. Detecta/instala JDK 17 Temurin en `/opt/jdk-17*` (idempotente).
2. Verifica `classes.jar` del módulo cloudkit (si falta: `./gradlew assembleRelease`).
3. Reconstruye el classpath (okhttp de caché gradle o Maven Central).
4. Compila `CloudKitSmokeDriver` y corre el smoke contra `matrix.csv`.
5. Reporta censo (resueltos / degradados / no cubiertos / throws).

Opcional, para URLs frescas de los 5 proveedores (animeav1, veranimes,
monoschinos, latanime, tvanime):
```bash
python3 tools/live-harness/harness.py --out tools/live-harness/matrix.csv --animes 2 5
bash tools/live-harness/run-smoke.sh
```

## Validación autoritativa (no depende del sandbox)

CI corre en cada push (GitHub Actions, runner Linux + Android SDK):
- `:core:testDebugUnitTest` — 143+ tests unitarios incl. concurrencia.
- `:extractor-cloudkit:testDebugUnitTest` — extractores con fixtures +
  `CloudKitMatrixLiveTest` (replay live de la matriz).

Si el sandbox local está roto, abrir un PR vacío fuerza a CI a validar TODO.

## Quirks conocidos del sandbox Base44 (importante)

- El cwd de bash se resetea a `/app/conversations/<id>/` entre turnos y a veces
  entre comandos: **usar SIEMPRE rutas absolutas o `cd /abs && ...` en la
  misma línea**.
- `/tmp` sobrevive dentro de una sesión pero no es confiable entre sesiones.
- `rm -rf` está bloqueado fuera de `/app`.
- tmux sirve para procesos largos (gradle), pero las sesiones mueren con el
  sandbox: no dejar trabajo importante solo ahí.
- El token de GitHub vive en `.agents/.env` del agente (GITHUB_TOKEN), no en
  el repo. El remote del repo ya lleva el token embebido (push funciona solo).

## Estado del entorno de estrés

- **StressLab RECONSTRUIDO y versionado (2026-10-10, commit b37df39):**
  `core/src/test/kotlin/io/vdl/core/QueueStressLabTest.kt` recrea los 10
  escenarios de caos (Q1–Q10): sumidero masivo 60 tareas, cancelación
  masiva en vuelo, regresiones de los 3 bugs corregidos (cancelada en cola
  no despacha, bomba inicial en start(), sin dispatch tras shutdown),
  tormenta pause/resume, shutdown con RUNNING, pausa viva, recuperación de
  huérfanas (contrato: RUNNING→PAUSED + resume) y arranque en frío con
  cancelAll+shutdown. Corre en CI en cada push; ya no puede perderse.
- Los 3 fixes de producción + `QueueManagerTest` siguen en el repo y en CI.
- Contratos documentados por el estrés: start() barre huérfanas RUNNING→PAUSED
  (nunca auto-despacha tras crash); shutdown es suspend con pausa sincrónica.
- Lección aplicada: TODO lo nuevo vive en el repo desde el día uno.
