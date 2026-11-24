#!/usr/bin/env bash
# 构建 TV arm64 包，并按「版本名 + versionCode」命名产物到 dist/
# 用法：./scripts/build-tv64.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$HOME/toolchain/jdk-17.0.21.1+2/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/toolchain/sdk}"

VERSION_NAME="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode *= *\([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
SHORT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"

echo "==> NextMovie ${VERSION_NAME} (versionCode ${VERSION_CODE}, ${SHORT_SHA})"
./gradlew :app:assembleTv64Debug

SRC="app/build/outputs/apk/tv64/debug/app-tv64-debug.apk"
mkdir -p dist
OUT="dist/NextMovie-${VERSION_NAME}-vc${VERSION_CODE}-arm64-v8a.apk"
cp "$SRC" "$OUT"

echo "==> $OUT"
ls -lh "$OUT"
shasum -a 256 "$OUT"
