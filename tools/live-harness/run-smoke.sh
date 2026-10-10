#!/usr/bin/env bash
# tools/live-harness/run-smoke.sh — entorno de smoke reproducible y PERSISTENTE.
#
# Problema que resuelve: el sandbox local se borra entre sesiones (JDK,
# /tmp, tmux). Este script reconstruye TODO el entorno de prueba local en
# ~2 minutos y es idempotente:
#
#   1. JDK 17 (Temurin) si no existe
#   2. OkHttp real extraído del cache de Gradle (o descargado de Maven Central)
#   3. Compila CloudKitSmokeDriver contra classes.jar del módulo
#   4. Ejecuta el smoke contra tools/live-harness/matrix.csv
#
# La fuente de verdad viva es el repo (GitHub): este script + harness.py +
# matrix.csv + CloudKitMatrixLiveTest viven en el repo, así que el entorno
# completo se reconstruye desde cualquier sandbox limpio con:
#   git clone <repo> && tools/live-harness/run-smoke.sh
#
# En CI (con Android SDK) no hace falta: ./gradlew :extractor-cloudkit:testDebugUnitTest
# corre CloudKitMatrixLiveTest sobre la misma matriz.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"

# ---- 1. JDK 17 ----------------------------------------------------------
JDK_DIR=""
for c in /opt/jdk-17* /usr/lib/jvm/*17*; do
  [ -x "$c/bin/java" ] && JDK_DIR="$c" && break
done
if [ -z "$JDK_DIR" ]; then
  echo "[smoke] descargando JDK 17 Temurin..."
  mkdir -p /opt
  curl -sL --max-time 180 -o /opt/jdk.tgz \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf /opt/jdk.tgz -C /opt
  rm -f /opt/jdk.tgz
  JDK_DIR="$(ls -d /opt/jdk-17* | head -1)"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
echo "[smoke] JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"

# ---- 2. classpath: classes.jar + okhttp + okio + stdlib + coroutines ----
CLASSES="extractor-cloudkit/build/intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar"
[ -f "$CLASSES" ] || { echo "[smoke] ERROR: falta $CLASSES — compila primero (CI lo hace en cada push)"; exit 1; }

DEPS=()
grab() { # grab <nombre> <dir-cache>
  local f
  f="$(find /root/.gradle/caches/modules-2 -name "$1" 2>/dev/null | grep -v sources | head -1 || true)"
  if [ -z "$f" ]; then
    f="/tmp/deps/$1"
    mkdir -p /tmp/deps
    [ -f "$f" ] || curl -sL --max-time 120 -o "$f" \
      "https://repo1.maven.org/maven2/$2/$1"
  fi
  DEPS+=("$f")
}
GRAB2() { # versión mínima: busca el jar en el cache con find genérico
  local f
  f="$(find /root/.gradle/caches/modules-2 -name "$1" 2>/dev/null | grep -v sources | head -1 || true)"
  [ -z "$f" ] && f="/tmp/okhttp-real.jar"
  DEPS+=("$f")
}
GRAB2 "okio-jvm-3.17.0.jar"
GRAB2 "kotlin-stdlib-2.2.21.jar"
GRAB2 "kotlinx-coroutines-core-jvm-1.11.0.jar"
# okhttp: en sandbox local puede estar ya extraído del cache de transforms
OKHTTP="$(find /root/.gradle/caches -name "okhttp-5*.jar" 2>/dev/null | grep -v sources | head -1 || true)"
[ -z "$OKHTTP" ] && [ -f /tmp/okhttp-real.jar ] && OKHTTP=/tmp/okhttp-real.jar
if [ -z "$OKHTTP" ]; then
  echo "[smoke] descargando okhttp 5.4.0 de Maven Central..."
  mkdir -p /tmp/deps
  curl -sL --max-time 120 -o /tmp/deps/okhttp-5.4.0.jar \
    "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.4.0/okhttp-5.4.0.jar"
  OKHTTP=/tmp/deps/okhttp-5.4.0.jar
fi
CP="$CLASSES:$OKHTTP:${DEPS[0]}"
for d in "${DEPS[@]:1}"; do CP="$CP:$d"; done
echo "[smoke] classpath listo"

# ---- 3. compilar driver -------------------------------------------------
mkdir -p /tmp/smoke-out
javac -cp "$CP" -d /tmp/smoke-out tools/live-harness/CloudKitSmokeDriver.java
echo "[smoke] driver compilado"

# ---- 4. ejecutar ---------------------------------------------------------
MATRIX="${1:-tools/live-harness/matrix.csv}"
echo "[smoke] corriendo contra $MATRIX ..."
java -cp "/tmp/smoke-out:$CP" CloudKitSmokeDriver "$MATRIX"
