#!/usr/bin/env bash
# 构建 TV 双 ABI 包（arm64-v8a + armeabi-v7a），并按「版本名 + versionCode」命名产物到 dist/
# 用法：
#   ./scripts/build-tv.sh        # 两个 ABI 都打
#   ./scripts/build-tv.sh 64     # 只打 64 位
#   ./scripts/build-tv.sh 32     # 只打 32 位
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$HOME/toolchain/jdk-17.0.21.1+2/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/toolchain/sdk}"

VERSION_NAME="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode *= *\([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
SHORT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"

WHICH="${1:-both}"
echo "==> NextMovie ${VERSION_NAME} (versionCode ${VERSION_CODE}, ${SHORT_SHA}) mode=${WHICH}"

case "$WHICH" in
  64)   TASKS=(":app:assembleTv64Debug") ;;
  32)   TASKS=(":app:assembleTvDebug") ;;
  both) TASKS=(":app:assembleTv64Debug" ":app:assembleTvDebug") ;;
  *)    echo "未知参数：$WHICH（可用 64 / 32 / both）" >&2; exit 2 ;;
esac

./gradlew "${TASKS[@]}"

mkdir -p dist
copy() {
  local src="$1" abi="$2"
  if [ ! -f "$src" ]; then echo "!! 缺少产物 $src" >&2; return 1; fi
  local out="dist/NextMovie-${VERSION_NAME}-vc${VERSION_CODE}-${abi}.apk"
  cp "$src" "$out"
  echo "==> $out  ($(du -h "$out" | cut -f1 | tr -d ' \t'))"
  shasum -a 256 "$out" | awk '{print "    sha256 "$1}'
}

if [ "$WHICH" = "64" ] || [ "$WHICH" = "both" ]; then
  copy "app/build/outputs/apk/tv64/debug/app-tv64-debug.apk" "arm64-v8a"
fi
if [ "$WHICH" = "32" ] || [ "$WHICH" = "both" ]; then
  copy "app/build/outputs/apk/tv/debug/app-tv-debug.apk" "armeabi-v7a"
fi
