#!/bin/sh
# Builds libghostty-vt (Ghostty's terminal-emulation library) for Android and
# installs the static library and headers under app/libghostty-vt/, where
# app/src/main/cpp/CMakeLists.txt links them into librexvt.so.
#
# Run it once after cloning, and again after changing scripts/ghostty.version.
#
#   GHOSTTY_SRC   Ghostty checkout to clone from (default ~/git/ghostty, else GitHub).
#                 The checkout is never modified; we build from a temporary
#                 `git clone --shared` at the pinned commit.
#   ZIG           zig binary (default: zig@0.16 from Homebrew, else `zig`).
#   ANDROID_HOME  Android SDK with an NDK installed (Ghostty's build finds the newest NDK).
#   ABIS          Android ABIs to build (default "arm64-v8a"; add x86_64 for Intel emulators).
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
pin=$(grep -v '^#' "$root/scripts/ghostty.version" | head -n1 | tr -d '[:space:]')
src=${GHOSTTY_SRC:-$HOME/git/ghostty}
abis=${ABIS:-arm64-v8a}
out="$root/app/libghostty-vt"

if [ -z "${ZIG:-}" ]; then
    if [ -x /opt/homebrew/opt/zig@0.16/bin/zig ]; then ZIG=/opt/homebrew/opt/zig@0.16/bin/zig; else ZIG=zig; fi
fi
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME
if ! ls -d "$ANDROID_HOME"/ndk/* >/dev/null 2>&1; then
    echo "No NDK under $ANDROID_HOME/ndk. Install one: sdkmanager \"ndk;<version>\"" >&2
    exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
if [ -d "$src/.git" ]; then
    git clone -q --shared "$src" "$tmp/ghostty"
else
    git clone -q https://github.com/ghostty-org/ghostty.git "$tmp/ghostty"
fi
git -C "$tmp/ghostty" checkout -q --detach "$pin"

need=$(sed -n 's/.*minimum_zig_version *= *"\([^"]*\)".*/\1/p' "$tmp/ghostty/build.zig.zon")
have=$("$ZIG" version)
case "$have" in
    "${need%.*}".*) ;;
    *) echo "Ghostty $pin wants zig $need; $ZIG is $have. Set ZIG=..." >&2; exit 1 ;;
esac

short=$(printf %.9s "$pin")
for abi in $abis; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android ;;
        x86_64) target=x86_64-linux-android ;;
        *) echo "Unsupported ABI $abi" >&2; exit 1 ;;
    esac
    echo "Building libghostty-vt $short for ${abi}..."
    (cd "$tmp/ghostty" && "$ZIG" build -Demit-lib-vt -Dtarget="$target" -Doptimize=ReleaseFast \
        -p "$tmp/out-$abi")
    mkdir -p "$out/$abi"
    cp "$tmp/out-$abi/lib/libghostty-vt.a" "$out/$abi/"
    rm -rf "$out/include"
    cp -R "$tmp/out-$abi/include" "$out/include"
done
echo "$pin" > "$out/VERSION"
echo "Installed into $out"
