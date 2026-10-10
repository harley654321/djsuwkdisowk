#!/usr/bin/env bash
# tools/env/bootstrap.sh — reconstrucción COMPLETA del entorno de pruebas
# desde un sandbox limpio. Idempotente: se puede correr las veces que sea.
#
#   bash tools/env/bootstrap.sh [--fresh-matrix]
#
#   --fresh-matrix  además regenera la matriz de 75 URLs live antes del smoke
#                   (requiere red; los tokens CDN de matrix.csv caducan).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"

echo "== [1/5] JDK 17 =="
JDK_DIR=""
for c in /opt/jdk-17* /usr/lib/jvm/*17*; do
  [ -x "$c/bin/java" ] && JDK_DIR="$c" && break
done
if [ -z "$JDK_DIR" ]; then
  mkdir -p /opt
  curl -sL --max-time 180 -o /opt/jdk.tgz \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf /opt/jdk.tgz -C /opt && rm -f /opt/jdk.tgz
  JDK_DIR="$(ls -d /opt/jdk-17* | head -1)"
fi
export JAVA_HOME="$JDK_DIR"; export PATH="$JAVA_HOME/bin:$PATH"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

echo "== [2/5] dependencias JVM (okhttp/okio/stdlib/coroutines) =="
mkdir -p /tmp/deps
OKHTTP=""
for f in /tmp/deps/okhttp-5.4.0.jar /tmp/okhttp-real.jar; do
  [ -f "$f" ] && OKHTTP="$f" && break
done
if [ -z "$OKHTTP" ]; then
  OKHTTP="$(find /root/.gradle/caches -name 'okhttp-5*.jar' 2>/dev/null | grep -v sources | head -1 || true)"
  if [ -z "$OKHTTP" ]; then
    curl -sL --max-time 120 -o /tmp/deps/okhttp-5.4.0.jar \
      "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.4.0/okhttp-5.4.0.jar"
    OKHTTP=/tmp/deps/okhttp-5.4.0.jar
  fi
fi
echo "okhttp: $OKHTTP"

echo "== [3/5] classes.jar del módulo cloudkit =="
CLASSES="extractor-cloudkit/build/intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar"
if [ ! -f "$CLASSES" ]; then
  echo "  faltante → ./gradlew :extractor-cloudkit:assembleRelease (puede tardar)"
  ./gradlew :extractor-cloudkit:assembleRelease --console=plain -q
fi
ls -la "$CLASSES"

echo "== [4/5] matriz de URLs =="
if [ "${1:-}" = "--fresh-matrix" ]; then
  python3 tools/live-harness/harness.py --out tools/live-harness/matrix.csv --animes 2 5
  echo "  matriz regenerada"
else
  echo "  usando matrix.csv existente (usar --fresh-matrix para regenerar)"
fi

echo "== [5/5] smoke masivo =="
bash tools/live-harness/run-smoke.sh

echo
echo "== bootstrap completo: censo arriba. Detalles en tools/env/RUNBOOK.md =="
