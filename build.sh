#!/bin/bash
# Portable manual build chain (no Gradle/AGP): aapt2 + javac + d8 + zipalign + apksigner.
# Same chain that produced the verified on-device builds; only paths are portable now.
#
# Requirements: JDK 17 (java/javac on PATH or JAVA_HOME), Android SDK with
# platform android-34 + build-tools 34.0.0 (ANDROID_HOME / ANDROID_SDK_ROOT,
# or ./tools/fetch-deps style local dir via ANDROID_SDK_HOME_OVERRIDE below),
# curl + unzip for tools/fetch-deps.sh, zip.
#
# Signing keystore resolution, first hit wins:
#   1. $KEYSTORE_FILE (path)                    — GitHub Actions uses this (from Secrets)
#   2. keystore/ikantv.jks (plain file)     — local use, never committed
#   3. keystore/ikantv.jks.b64 (base64)     — decoded to build-manual/keystore.jks
# Passwords come from $KEYSTORE_PASSWORD / $KEY_PASSWORD (defaults match the
# long-standing local keystore so CI needs no extra secrets for them).
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=6
VERSION_NAME=2.8.5
APP_ID=com.lev1s.ikantv

# --- toolchain ---
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then echo "ERROR: set ANDROID_HOME or ANDROID_SDK_ROOT" >&2; exit 1; fi
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
export PATH="$BT:$PATH"
for t in java javac aapt2 d8 zipalign apksigner zip curl unzip; do
  command -v "$t" >/dev/null || { echo "ERROR: missing tool: $t" >&2; exit 1; }
done

# --- restore binary assets (icons, banner, glyphicons fonts) from base64 ---
# The repo stores them as ci-assets/*.b64 text; see ci-assets/MANIFEST.txt.
while read -r sha path; do
  case "$sha" in \#*|"") continue;; esac
  b64="ci-assets/$(echo "$path" | tr '/' '_').b64"
  if [ ! -f "$path" ]; then
    mkdir -p "$(dirname "$path")"
    base64 -d "$b64" > "$path"
  fi
  echo "$sha  $path" | sha256sum -c - >/dev/null || { echo "ERROR: sha256 mismatch: $path" >&2; exit 1; }
done < ci-assets/MANIFEST.txt
echo "[0/6] binary assets restored + verified"

# --- dependencies (libVLC AAR pieces + jsoup jar, sha256-pinned) ---
bash tools/fetch-deps.sh
VLC_DIR=build-deps/vlc/x
VLC_JAR=$VLC_DIR/classes.jar
JSOUP_JAR=build-deps/jsoup-1.18.3.jar

# --- keystore ---
OUT=build-manual
rm -rf $OUT && mkdir -p $OUT/gen $OUT/classes $OUT/dex
KS_PASS="${KEYSTORE_PASSWORD:-ikantv}"
KEY_PASS="${KEY_PASSWORD:-ikantv}"
KEY_ALIAS="${KEY_ALIAS:-ikantv}"
if [ -n "${KEYSTORE_FILE:-}" ]; then
  KS="$KEYSTORE_FILE"
elif [ -f keystore/ikantv.jks ]; then
  KS=keystore/ikantv.jks
elif [ -f keystore/ikantv.jks.b64 ]; then
  base64 -d keystore/ikantv.jks.b64 > $OUT/keystore.jks
  KS=$OUT/keystore.jks
else
  echo "ERROR: no signing keystore found (KEYSTORE_FILE / keystore/ikantv.jks[.b64])" >&2; exit 1
fi

APP=app/src/main
echo "[1/6] aapt2 compile resources"
aapt2 compile --dir $APP/res -o $OUT/compiled.zip
# libVLC layouts (VLCVideoLayout inflates them at runtime and reads org.videolan.R):
# they must be compiled in under the library package or the player crashes on open.
aapt2 compile --dir thirdparty/vlc-res -o $OUT/vlc-compiled.zip

echo "[2/6] aapt2 link"
# Manual chain needs package= in the manifest (Gradle used namespace): make a temp copy.
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$APP_ID\">|" \
  $APP/AndroidManifest.xml > $OUT/AndroidManifest.xml
aapt2 link -o $OUT/app-unsigned.apk \
  -I $ANDROID_JAR \
  --manifest $OUT/AndroidManifest.xml \
  --java $OUT/gen \
  --extra-packages org.videolan \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  --min-sdk-version 28 --target-sdk-version 34 \
  -A $APP/assets \
  $OUT/compiled.zip $OUT/vlc-compiled.zip

echo "[3/6] javac"
find $APP/java $OUT/gen -name '*.java' > $OUT/sources.txt
javac -encoding UTF-8 -cp $ANDROID_JAR:$VLC_JAR:$JSOUP_JAR -d $OUT/classes @$OUT/sources.txt

echo "[4/6] d8 -> classes.dex"
d8 --release --lib $ANDROID_JAR --output $OUT/dex $(find $OUT/classes -name '*.class') $VLC_JAR $JSOUP_JAR

echo "[5/6] pack dex + native libs + zipalign"
cp $OUT/app-unsigned.apk $OUT/app-with-dex.apk
(cd $OUT/dex && zip -q ../app-with-dex.apk classes.dex)
mkdir -p $OUT/nativelib/lib/armeabi-v7a
cp $VLC_DIR/jni/armeabi-v7a/*.so $OUT/nativelib/lib/armeabi-v7a/
(cd $OUT/nativelib && zip -q -9 ../app-with-dex.apk lib/armeabi-v7a/libvlc.so lib/armeabi-v7a/libvlcjni.so lib/armeabi-v7a/libc++_shared.so)
zipalign -f -p 4 $OUT/app-with-dex.apk $OUT/app-aligned.apk

echo "[6/6] sign"
apksigner sign \
  --ks "$KS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --out $OUT/ikantv.apk \
  $OUT/app-aligned.apk

apksigner verify --print-certs $OUT/ikantv.apk | head -3
ls -la $OUT/ikantv.apk
echo "BUILD OK -> $OUT/ikantv.apk"
