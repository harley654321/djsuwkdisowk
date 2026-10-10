# Live harness (entorno de pruebas MASIVO — reconstruible, nunca se pierde)

Este directorio ES el entorno de pruebas live del proyecto. Todo lo que se
necesita para regenerarlo vive aquí y en GitHub, así que ningún reset del
sandbox local lo destruye:

- `harness.py` — extrae URLs frescas de los proveedores vía sus APIs
  (animeav1, veranimes, monoschinos, latanime, tvanime) y genera `matrix.csv`.
- `matrix.csv` — matriz de 75 URLs reales (5 proveedores × servidores).
- `CloudKitSmokeDriver.java` + `run-smoke.sh` — smoke JVM sin Android SDK:
  reconstruye JDK 17, OkHttp y dependencias, compila el driver contra
  `classes.jar` y resuelve TODA la matriz en paralelo (6 workers).
- `census-*.md` — censo por host con clasificación y decisiones.
- `CloudKitMatrixLiveTest` (en extractor-cloudkit/src/test) — el mismo replay
  como test unitario marcado live; corre en CI con SDK completo.

## Regenerar desde cero en cualquier sandbox limpio
```bash
git clone https://github.com/harley654321/djsuwkdisowk && cd djsuwkdisowk
python3 tools/live-harness/harness.py --out tools/live-harness/matrix.csv --animes 2 5
bash tools/live-harness/run-smoke.sh
```
CI valida lo mismo en cada push: `:extractor-cloudkit:testDebugUnitTest`.
