#!/usr/bin/env sh
# Stages N independent copies ("slots") of the native libraries onto the classpath.
#
# Usage: stage-natives.sh <platform> <slots> <renderer-dir> <pdfium-dir> <dest-dir>
#
# Produces <dest-dir>/slot-0 .. slot-<N-1>, each holding its own copy of libpdf_render and
# libpdfium. Every slot is dlopen'ed separately by the JVM, giving each one a private pdfium
# instance, which is how the Java side achieves N-way render concurrency despite pdfium
# itself being single-threaded.
set -eu

PLATFORM="${1:?platform required}"
SLOTS="${2:?slot count required}"
RENDERER_DIR="${3:?renderer dir required}"
PDFIUM_DIR="${4:?pdfium dir required}"
DEST="${5:?destination dir required}"

case "$PLATFORM" in
  darwin-*)  RENDERER="libpdf_render.dylib"; PDFIUM="libpdfium.dylib" ;;
  linux-*)   RENDERER="libpdf_render.so";    PDFIUM="libpdfium.so" ;;
  windows-*) RENDERER="pdf_render.dll";      PDFIUM="pdfium.dll" ;;
  *) echo "stage-natives.sh: unsupported platform '$PLATFORM'" >&2; exit 1 ;;
esac

case "$SLOTS" in
  ''|*[!0-9]*|0) echo "stage-natives.sh: slot count must be a positive integer, got '$SLOTS'" >&2; exit 1 ;;
esac

[ -f "$RENDERER_DIR/$RENDERER" ] || { echo "stage-natives.sh: missing $RENDERER_DIR/$RENDERER (run cargo build --release)" >&2; exit 1; }
[ -f "$PDFIUM_DIR/$PDFIUM" ]     || { echo "stage-natives.sh: missing $PDFIUM_DIR/$PDFIUM (run fetch-pdfium.sh)" >&2; exit 1; }

# Start clean so lowering the slot count does not leave stale slots behind.
rm -rf "$DEST"
mkdir -p "$DEST"

i=0
while [ "$i" -lt "$SLOTS" ]; do
  mkdir -p "$DEST/slot-$i"
  cp "$RENDERER_DIR/$RENDERER" "$DEST/slot-$i/$RENDERER"
  cp "$PDFIUM_DIR/$PDFIUM"     "$DEST/slot-$i/$PDFIUM"
  i=$((i + 1))
done

echo "Staged $SLOTS native slot(s) for $PLATFORM into $DEST"
