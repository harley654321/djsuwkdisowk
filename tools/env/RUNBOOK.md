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

## Estado del entorno de estrés (honesto)

- **Lo que SÍ está versionado y seguro:** los 3 fixes de producción del
  QueueManager (shutdown race, bomba inicial en start(), dispatch durante
  apagado) + `QueueManagerTest` con tests de concurrencia (orphan sweep,
  runningOrphans, pump) corriendo en CI en cada push.
- **Lo que se PERDIÓ:** el harness de caos Q1–Q10 original (se construyó en
  un árbol fuera del repo entre sesiones del 2026-10-10 01:11–01:50).
  La lección ya está aplicada: TODO lo nuevo vive en el repo (live-harness
  quedó 100% versionado el mismo día).
- **Pendiente:** reconstruir StressLab como módulo versionado (p.ej.
  `tools/stresslab/` o tests core dedicados) para re-probar caos de cola,
  cancelaciones masivas, pausado y cortes de red bajo concurrencia.
