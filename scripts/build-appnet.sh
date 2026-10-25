#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOLS=${APPNET_TOOLS:-/private/tmp/x2-tailscale-tools}
OUT="$ROOT/app/libs/libappnet.aar"
PATCH="$ROOT/third_party/appnet/patches/tailscale-strict-dial.patch"
LOGS_PATCH="$ROOT/third_party/appnet/patches/tailscale-logsdir-logtail.patch"
TS_VERSION=v1.104.2
TS_CORE_COMMIT=e26db7a5797683ec99ad1516c40b628c7f2dfd71
STAMP_VERSION=${TS_VERSION#v}
MOBILE_VERSION=v0.0.0-20240806205939-81131f6468ab

export GOROOT="$TOOLS/go"
export GOTOOLCHAIN=local
export GOPATH="$TOOLS/gopath"
export GOCACHE="$TOOLS/cache/go-build"
export GOMODCACHE="$TOOLS/cache/mod"
export TMPDIR="$TOOLS/tmp"
export PATH="$GOROOT/bin:$PATH"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$TOOLS/android-ndk-r23b"
export GOFLAGS=-mod=mod

[[ -x "$GOROOT/bin/go" && -d "$ANDROID_NDK_HOME" && -f "$PATCH" && -f "$LOGS_PATCH" ]] || {
  echo 'Missing pinned Go, NDK, or appnet patch' >&2
  exit 1
}
grep -q '23.1.7779620' "$ANDROID_NDK_HOME/source.properties"
APP_COMMIT=$(git -C "$ROOT" rev-parse HEAD)
APP_SHORT_COMMIT=$(git -C "$ROOT" rev-parse --short=9 HEAD)
LONG_STAMP="$STAMP_VERSION-t${TS_CORE_COMMIT:0:9}-g$APP_SHORT_COMMIT"

mkdir -p "$TMPDIR"
[[ -x "$TOOLS/bin/gomobile" && -x "$TOOLS/bin/gobind" ]] || {
  echo "Missing pinned gomobile/gobind. Run: (cd $TOOLS/cache/mod/golang.org/x/mobile@$MOBILE_VERSION/cmd/gomobile && go install . ../gobind)" >&2
  exit 1
}
export PATH="$TOOLS/bin:$PATH"

BUILD=$(mktemp -d "$TMPDIR/appnet-build.XXXXXX")
trap 'rm -rf "$BUILD"' EXIT
cp -R "$ROOT/native/appnet/." "$BUILD/appnet/"
rm -rf "$BUILD/appnet/vendor"
mkdir -p "$BUILD/tailscale.com"
cp -R "$TOOLS/cache/mod/tailscale.com@$TS_VERSION/." "$BUILD/tailscale.com/"
chmod -R u+w "$BUILD"
(cd "$BUILD/tailscale.com" && patch -p3 < "$PATCH")
(cd "$BUILD/tailscale.com" && patch -p3 < "$LOGS_PATCH")

cd "$BUILD/appnet"
go mod edit -replace "tailscale.com=$BUILD/tailscale.com"

gomobile bind -target android/arm64,android/arm -androidapi 26 \
  -tags ts_omit_logtail \
  -ldflags "-linkmode=external -extldflags=-Wl,-z,max-page-size=16384 \
    -X tailscale.com/version.longStamp=$LONG_STAMP \
    -X tailscale.com/version.shortStamp=$STAMP_VERSION \
    -X tailscale.com/version.gitCommitStamp=$TS_CORE_COMMIT \
    -X tailscale.com/version.extraGitCommitStamp=$APP_COMMIT" \
  -o "$TOOLS/libappnet_unstripped.aar" ./

STAGE="$BUILD/aar"
mkdir -p "$STAGE"
unzip -q "$TOOLS/libappnet_unstripped.aar" -d "$STAGE"
LLVM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for ABI in arm64-v8a armeabi-v7a; do
  SO="$STAGE/jni/$ABI/libgojni.so"
  [[ -s "$SO" ]]
  "$LLVM/llvm-strip" --strip-debug "$SO"
  "$LLVM/llvm-readelf" -lW "$SO" | awk '/ LOAD / { n++; if ($NF != "0x4000" && $NF != "0x10000") bad=1 } END { exit (n && !bad) ? 0 : 1 }'
done

mkdir -p "$ROOT/app/libs"
rm -f "$OUT"
(cd "$STAGE" && zip -qr "$OUT" .)
cd "$ROOT"
shasum -a 256 app/libs/libappnet.aar | tee "$ROOT/third_party/appnet/SHA256SUMS"
unzip -l "$OUT" | grep -E 'libgojni.so|classes.jar'
