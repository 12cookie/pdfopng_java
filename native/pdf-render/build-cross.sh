#!/usr/bin/env sh
# Cross-compiles libpdf_render for another platform and stages N slots for it, so a jar built on
# one OS can also run on another (the Java side picks the right native/<platform>/ directory at
# run time). Uses cargo-zigbuild (https://github.com/rust-cross/cargo-zigbuild): zig provides a
# portable C linker and glibc stubs, so no per-target GCC toolchain is needed.
#
# Usage: build-cross.sh <platform> <slots> <dest-dir>
#   platform: linux-x86_64 | linux-aarch64
#   dest-dir: e.g. target/classes/native/<platform>
#
# Prerequisites (one-off):
#   brew install zig            (or any zig >= 0.13)
#   cargo install cargo-zigbuild
#   rustup target add x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu
set -eu

PLATFORM="${1:?platform required (linux-x86_64 | linux-aarch64)}"
SLOTS="${2:?slot count required}"
DEST="${3:?destination dir required}"

# Oldest glibc the binary will run on. 2.17 (RHEL 7 / Ubuntu 14.04 era) covers every current
# distro; pdfium's own prebuilt binary requires a newer glibc anyway, so this is not the bottleneck.
GLIBC="${GLIBC_VERSION:-2.17}"

case "$PLATFORM" in
  linux-x86_64)  RUST_TARGET="x86_64-unknown-linux-gnu" ;;
  linux-aarch64) RUST_TARGET="aarch64-unknown-linux-gnu" ;;
  *) echo "build-cross.sh: unsupported platform '$PLATFORM' (linux-x86_64 | linux-aarch64)" >&2; exit 1 ;;
esac

for tool in cargo-zigbuild zig; do
  command -v "$tool" >/dev/null 2>&1 || { echo "build-cross.sh: '$tool' not found; see prerequisites in this script" >&2; exit 1; }
done
rustup target list --installed 2>/dev/null | grep -qx "$RUST_TARGET" \
  || { echo "build-cross.sh: Rust target $RUST_TARGET not installed; run: rustup target add $RUST_TARGET" >&2; exit 1; }

DIR="$(cd "$(dirname "$0")" && pwd)"

echo "==> pdfium for $PLATFORM"
"$DIR/fetch-pdfium.sh" "$PLATFORM"

echo "==> cargo zigbuild --release --target $RUST_TARGET.$GLIBC"
(cd "$DIR" && cargo zigbuild --release --target "$RUST_TARGET.$GLIBC")

echo "==> staging $SLOTS slot(s)"
"$DIR/stage-natives.sh" "$PLATFORM" "$SLOTS" "$DIR/target/$RUST_TARGET/release" "$DIR/lib/$PLATFORM" "$DEST"
