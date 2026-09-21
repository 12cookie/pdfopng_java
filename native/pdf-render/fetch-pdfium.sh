#!/usr/bin/env sh
# Downloads the prebuilt pdfium binary matching the pdfium-render version in Cargo.toml.
# Usage: fetch-pdfium.sh <platform>   where platform is e.g. darwin-aarch64, linux-x86_64
# The binary is placed in lib/<platform>/ and skipped if already present.
set -eu

# Must match the `pdfium_XXXX` feature enabled by pdfium-render's `pdfium_latest` (0.9.4 -> 7881).
PDFIUM_BUILD="${PDFIUM_BUILD:-7881}"
PLATFORM="${1:?platform argument required, e.g. darwin-aarch64}"

case "$PLATFORM" in
  darwin-aarch64) ASSET="pdfium-mac-arm64.tgz";   MEMBER="lib/libpdfium.dylib" ;;
  darwin-x86_64)  ASSET="pdfium-mac-x64.tgz";     MEMBER="lib/libpdfium.dylib" ;;
  linux-x86_64)   ASSET="pdfium-linux-x64.tgz";   MEMBER="lib/libpdfium.so" ;;
  linux-aarch64)  ASSET="pdfium-linux-arm64.tgz"; MEMBER="lib/libpdfium.so" ;;
  windows-x86_64) ASSET="pdfium-win-x64.tgz";     MEMBER="bin/pdfium.dll" ;;
  *) echo "fetch-pdfium.sh: unsupported platform '$PLATFORM'" >&2; exit 1 ;;
esac

DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$DIR/lib/$PLATFORM"
OUT_FILE="$OUT_DIR/$(basename "$MEMBER")"

if [ -f "$OUT_FILE" ]; then
  echo "pdfium already present at $OUT_FILE"
  exit 0
fi

URL="https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F${PDFIUM_BUILD}/${ASSET}"
echo "Downloading pdfium ${PDFIUM_BUILD} for ${PLATFORM} from ${URL}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL "$URL" -o "$TMP/$ASSET"
tar -xzf "$TMP/$ASSET" -C "$TMP" "$MEMBER"

mkdir -p "$OUT_DIR"
mv "$TMP/$MEMBER" "$OUT_FILE"
echo "Saved $OUT_FILE"
