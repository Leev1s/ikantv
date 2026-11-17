#!/bin/bash
# Text-only bootstrap: fetch Gradle rather than committing its binary wrapper.
set -euo pipefail
cd "$(dirname "$0")/.."
GRADLE_VERSION=8.14.7
GRADLE_SHA256=903bfc4bc7622323124054c10f509246212a1902948bdaf26c379cca8735e856
CACHE="${IKANTV_BUILD_CACHE:-$PWD/build-cache}"
ZIP="$CACHE/gradle-$GRADLE_VERSION-bin.zip"
DIST="$CACHE/gradle-$GRADLE_VERSION"
mkdir -p "$CACHE"
if [ ! -f "$ZIP" ] || ! echo "$GRADLE_SHA256  $ZIP" | shasum -a 256 -c - >/dev/null 2>&1; then
    curl -fSL --retry 3 --connect-timeout 20 \
        "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP.tmp"
    echo "$GRADLE_SHA256  $ZIP.tmp" | shasum -a 256 -c -
    mv "$ZIP.tmp" "$ZIP"
fi
if [ ! -x "$DIST/bin/gradle" ]; then unzip -qo "$ZIP" -d "$CACHE"; fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$CACHE/user-home}"
exec "$DIST/bin/gradle" --console=plain "$@"
