#!/bin/bash
# 3.0 release entry: restore assets/dependencies, test, build with AGP, verify.
# Version values below are also read by app/build.gradle. Never duplicate them.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=16
VERSION_NAME=3.0.0
APP_ID=com.lev1s.ikantv

if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then echo "ERROR: set ANDROID_HOME or ANDROID_SDK_ROOT" >&2; exit 1; fi
export ANDROID_HOME="$SDK"
for t in java javac curl unzip zip shasum; do
    command -v "$t" >/dev/null || { echo "ERROR: missing tool: $t" >&2; exit 1; }
done
[ -f "$SDK/platforms/android-34/android.jar" ] || { echo "ERROR: Android platform 34 missing" >&2; exit 1; }
[ -x "$SDK/build-tools/34.0.0/apksigner" ] || { echo "ERROR: build-tools 34.0.0 missing" >&2; exit 1; }

while read -r sha path; do
  case "$sha" in \#*|"") continue;; esac
  b64="ci-assets/$(echo "$path" | tr '/' '_').b64"
  mkdir -p "$(dirname "$path")"
  base64 -d < "$b64" > "$path.tmp" || { rm -f "$path.tmp"; exit 1; }
  if ! echo "$sha  $path.tmp" | shasum -a 256 -c - >/dev/null; then
    rm -f "$path.tmp"
    echo "ERROR: sha256 mismatch: $path" >&2
    exit 1
  fi
  mv "$path.tmp" "$path"
done < ci-assets/MANIFEST.txt
echo "[1/4] binary assets restored + verified"

echo "[2/4] pinned dependencies"
bash tools/fetch-deps.sh
OUT="$PWD/build-manual"
mkdir -p "$OUT"
rm -f "$OUT/ikantv.apk"
KS_PASS="${KEYSTORE_PASSWORD:-ikantv}"
KEY_PASS="${KEY_PASSWORD:-ikantv}"
KEY_ALIAS="${KEY_ALIAS:-ikantv}"
if [ -n "${KEYSTORE_FILE:-}" ]; then
  KS="$KEYSTORE_FILE"
elif [ -f keystore/ikantv.jks ]; then
  KS=keystore/ikantv.jks
elif [ -f keystore/ikantv.jks.b64 ]; then
  base64 -d < keystore/ikantv.jks.b64 > "$OUT/keystore.jks"
  KS=$OUT/keystore.jks
else
  echo "ERROR: no signing keystore found (KEYSTORE_FILE / keystore/ikantv.jks[.b64])" >&2; exit 1
fi

KS="$(cd "$(dirname "$KS")" && pwd)/$(basename "$KS")"
export IKANTV_KEYSTORE_FILE="$KS"
export IKANTV_STORE_PASSWORD="$KS_PASS"
export IKANTV_KEY_PASSWORD="$KEY_PASS"
export IKANTV_KEY_ALIAS="$KEY_ALIAS"

echo "[3/4] core regressions + Gradle release build"
bash tools/gradle.sh :app:testReleaseUnitTest :app:assembleRelease "$@"
cp app/build/outputs/apk/release/app-release.apk "$OUT/ikantv.apk"
echo "[4/4] verify release identity and contents"
bash tools/verify-apk.sh "$OUT/ikantv.apk" "$VERSION_CODE" "$VERSION_NAME"
echo "BUILD OK -> build-manual/ikantv.apk"
