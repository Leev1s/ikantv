#!/bin/bash
# Fetch the two external dependencies from Maven Central and verify SHA-256.
# Nothing binary is committed to this repo; deps land in build-deps/ (ignored).
#   - libVLC for Android 3.5.1 (org.videolan.android:libvlc-all) — player core.
#     The AAR in use locally was verified byte-identical to this Maven artifact
#     (MD5 75a4f54b6d88df295e2322e78d825965) on 2026-11-05.
#   - Jsoup 1.18.3 — HTML parsing for the native browsing layer.
set -euo pipefail
cd "$(dirname "$0")/.."
DEPS=build-deps
mkdir -p "$DEPS"
cd $DEPS

dl() { # url sha256 dest
  if [ -f "$3" ] && echo "$2  $3" | shasum -a 256 -c - >/dev/null 2>&1; then
    echo "deps: $3 already present, sha256 ok"
  else
    echo "deps: downloading $3"
    curl -fSL --retry 3 -o "$3.tmp" "$1"
    echo "$2  $3.tmp" | shasum -a 256 -c -
    mv "$3.tmp" "$3"
  fi
}

dl https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/3.5.1/libvlc-all-3.5.1.aar \
   42996e6e92b841d7581097b2ad2666b4791c11529292b83accb8068227db5414 libvlc-all-3.5.1.aar
dl https://repo1.maven.org/maven2/org/jsoup/jsoup/1.18.3/jsoup-1.18.3.jar \
   5be1ccd3228ae5fd6eed1bd6d827bac2bc65b91c20e9957d16ea65f739f15302 jsoup-1.18.3.jar

# AGP consumes the complete AAR and merges its resources, assets and native libs.
echo "deps OK -> build-deps/ (libvlc-all 3.5.1, jsoup 1.18.3)"
