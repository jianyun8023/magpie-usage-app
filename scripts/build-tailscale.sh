#!/usr/bin/env bash
# Build the official Android engine; prerequisites are documented in third_party/tailscale/README.md.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=${TAILSCALE_SRC:-/private/tmp/x2-tailscale-src}
TOOLS=${TAILSCALE_TOOLS:-/private/tmp/x2-tailscale-tools}
COMMIT=b01fa89d5ded31492c98c881207736c39c8f329e
TAG=1.104.2-tc3430f0d2-gb01fa89d5
if [[ ! -d "$SRC" ]]; then
  git clone --depth 1 --branch "$TAG" https://github.com/tailscale/tailscale-android.git "$SRC"
fi
[[ $(git -C "$SRC" rev-parse HEAD) == "$COMMIT" ]] || { echo 'Unexpected source commit' >&2; exit 1; }
[[ -z $(git -C "$SRC" status --porcelain --untracked-files=no) ]] || { echo 'Tracked upstream source has modifications' >&2; exit 1; }
export TOOLCHAINDIR="$TOOLS/go"
export GOROOT="$TOOLCHAINDIR"
export GOTOOLCHAIN=local
export GOPATH="$TOOLS/gopath" GOCACHE="$TOOLS/cache/go-build" GOMODCACHE="$TOOLS/cache/mod"
export GOBIN="$TOOLS/bin" TMPDIR="$TOOLS/tmp"
export TS_MKVERSION_OSS_GIT_CACHE="$TOOLS/tailscale-oss"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$TOOLS/android-ndk-r23b"
export PATH="$GOBIN:$GOROOT/bin:$PATH"
unset TOOLCHAIN_DIR # build-tags.sh must select the pinned Tailscale Go toolchain.
[[ -x "$GOROOT/bin/go" && -d "$ANDROID_NDK_HOME" ]] || { echo 'Install pinned Go and NDK prerequisites first' >&2; exit 1; }
grep -q '23.1.7779620' "$ANDROID_NDK_HOME/source.properties"
[[ $(cat "$SRC/go.toolchain.rev") == 7ef7b403648ac3a9481a6e88e8b894eff50b0167 ]]
mkdir -p "$GOBIN" "$TMPDIR" "$ROOT/app/libs"
cd "$SRC"
./tool/go version
./tool/go install golang.org/x/mobile/cmd/gobind golang.org/x/mobile/cmd/gomobile
./tool/go run tailscale.com/cmd/mkversion > tailscale.version
BUILD_TAGS="$(./build-tags.sh) ts_omit_cachenetmap"
VERSION_FLAGS=$(./version-ldflags.sh)
"$GOBIN/gomobile" bind -target android/arm64,android/arm -androidapi 26 \
  -tags "$BUILD_TAGS" \
  -ldflags "-linkmode=external -extldflags=-Wl,-z,max-page-size=16384 $VERSION_FLAGS" \
  -o "$TOOLS/libtailscale_unstripped.aar" ./libtailscale
STAGE=$(mktemp -d "$TOOLS/aar.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
unzip -q "$TOOLS/libtailscale_unstripped.aar" -d "$STAGE"
LLVM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for ABI in arm64-v8a armeabi-v7a; do
  SO="$STAGE/jni/$ABI/libgojni.so"
  [[ -s "$SO" ]]
  "$LLVM/llvm-strip" --strip-debug "$SO"
  "$LLVM/llvm-readelf" -lW "$SO" | awk '/ LOAD / { n++; if ($NF != "0x4000" && $NF != "0x10000") exit 1 } END { if (!n) exit 1 }'
done
[[ -s "$STAGE/classes.jar" ]]
rm -f "$TOOLS/libtailscale.aar"
(cd "$STAGE" && zip -qr "$TOOLS/libtailscale.aar" .)
cp "$TOOLS/libtailscale.aar" "$ROOT/app/libs/libtailscale.aar"
shasum -a 256 "$ROOT/app/libs/libtailscale.aar"
unzip -l "$ROOT/app/libs/libtailscale.aar" | grep -E 'libgojni.so|classes.jar'
