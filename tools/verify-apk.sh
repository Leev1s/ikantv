#!/bin/bash
# Verify the artifact, rather than merely printing package metadata in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
APK="${1:-build-manual/ikantv.apk}"
VERSION_CODE="${2:-$(sed -n 's/^VERSION_CODE=//p' build.sh)}"
VERSION_NAME="${3:-$(sed -n 's/^VERSION_NAME=//p' build.sh)}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$SDK/build-tools/34.0.0"
fail() { echo "ERROR: $*" >&2; exit 1; }

certs=$("$BT/apksigner" verify --print-certs "$APK")
digest=$(printf '%s\n' "$certs" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')
[ "$digest" = 945801887001b69f1d0a557ff58f8263e6374a07584d257b0046e5687e9e21ff ] || fail 'release signing identity changed'

badging=$("$BT/aapt2" dump badging "$APK")
# Exact fields avoid accepting a package with the expected text elsewhere.
first="${badging%%$'\n'*}"
[[ "$first" == "package: name='com.lev1s.ikantv' "* ]] || fail 'wrong applicationId'
[[ "$first" == *"versionCode='$VERSION_CODE' "* ]] || fail 'wrong versionCode'
[[ "$first" == *"versionName='$VERSION_NAME' "* ]] || fail 'wrong versionName'
grep -Fxq "sdkVersion:'28'" <<< "$badging" || fail 'minSdk must remain 28'
grep -Fxq "targetSdkVersion:'34'" <<< "$badging" || fail 'targetSdk must remain 34'
grep -Fxq "native-code: 'armeabi-v7a'" <<< "$badging" || fail 'unexpected native ABI set'

entries=$(unzip -Z1 "$APK")
for lib in libvlc.so libvlcjni.so libc++_shared.so; do
    grep -Fxq "lib/armeabi-v7a/$lib" <<< "$entries" || fail "missing $lib"
done
resources=$("$BT/aapt2" dump resources "$APK")
for layout in vlc_video_layout surface_view texture_view; do
    grep -Eq "layout/$layout([ :]|$)" <<< "$resources" || fail "missing VLC layout $layout"
done
dexstrings=$(unzip -p "$APK" 'classes*.dex' | strings)
grep -Fq 'Lorg/videolan/R$layout;' <<< "$dexstrings" || fail 'missing org.videolan.R layout class'
echo "$first"
echo "verified: release certificate, SDK, armeabi-v7a and VLC resources"
