# pdf-render

Rust `cdylib` that wraps [pdfium-render](https://crates.io/crates/pdfium-render) behind a
small C ABI so the Spring Boot app can render PDF pages to PNG in-process through Java's
FFM API (`java.lang.foreign`). The Java side lives in `com.example.demo.pdf`.

## C ABI (`src/lib.rs`)

| Function                                                                                    | Purpose                                                                                                                                                                             |
|---------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `pdfrender_init(const char* pdfium_path)`                                                   | Load `libpdfium`. Path may be a file, a directory, or `NULL` (search `PDFIUM_LIBRARY_PATH`, `/opt/homebrew/lib`, `/usr/local/lib`, `/usr/lib`, then the system loader). Idempotent. |
| `pdfrender_page_count(data, len, uint32_t* out)`                                            | Number of pages in the PDF bytes.                                                                                                                                                   |
| `pdfrender_render_page_png(data, len, page_index, dpi, uint8_t** out_ptr, size_t* out_len)` | Render a 0-based page to PNG (RGB8). `dpi = 0` means 150. Release the buffer with `pdfrender_free`.                                                                                 |
| `pdfrender_free(ptr, len)`                                                                  | Free a buffer returned by the call above.                                                                                                                                           |
| `pdfrender_last_error(buf, cap)`                                                            | Copy the calling thread's last error message into `buf`; returns bytes written.                                                                                                     |

All functions return `0` on success or a negative code (`ERR_*` constants, mirrored in
`PdfRenderException`) on failure.

## Build

The Maven build runs these steps automatically in `generate-resources`:

1. `fetch-pdfium.sh <platform>` downloads the pdfium binary pinned to the build that
   pdfium-render expects (currently **7881**) into `lib/<platform>/` — only once.
2. `cargo build --release` produces `target/release/libpdf_render.{dylib,so}`.

3. `stage-natives.sh` copies both libraries `${pdf.render.slots}` times (default 6) into
   `target/classes/native/<platform>/slot-0 .. slot-N-1`, so the jar is self-contained.

Use `mvn -DskipNative` to skip steps 1–2 and reuse the previous outputs; step 3 always runs.

## Why N copies? (render concurrency)

pdfium keeps global state and must only be called from one thread at a time. `libpdf_render`
holds a single global `Pdfium` and `dlopen`s `libpdfium`, so one copy of the pair equals one
renderer. Loading N *distinct files* makes the dynamic loader map N separate images with
separate globals, giving N independent renderers that can run in parallel. Both libraries must
be duplicated per slot: if every `libpdf_render` copy opened the same `libpdfium` path they
would share a single pdfium image again.

The Java side (`PdfRenderService`) loads every slot separately via `SymbolLookup.libraryLookup`
and keeps the renderers in a bounded queue that acts as the semaphore: taking one is acquiring
a permit, returning it releases the permit. Pool size is `pdf.render.concurrency` (0 = all
bundled slots; it cannot exceed the bundled count). Each slot costs ~9 MB of jar size and ~10 MB
of resident memory.

Measured on an M-series Mac, 48 parallel renders of a 2-page resume at 500 dpi:
1 slot 3.6 s, 3 slots 1.4 s, 6 slots 1.0 s.

`<platform>` is `darwin-aarch64`, `darwin-x86_64`, `linux-x86_64` or `linux-aarch64`, selected
by Maven profiles in `pom.xml` and matched at runtime by `NativeLibraryLoader.platform()`.

## Building for Linux (cross-compiling from macOS)

pdfium ships prebuilt for Linux, so only `libpdf_render` needs compiling, and Rust can
cross-compile it with [cargo-zigbuild](https://github.com/rust-cross/cargo-zigbuild) (zig acts
as a portable linker; no GCC cross-toolchain or Docker required).

One-off setup:

```bash
brew install zig
cargo install cargo-zigbuild
rustup target add x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu
```

Then activate the matching Maven profile(s); the host platform's slots are still built, so
tests keep running on the Mac, and the Linux slots are bundled *in addition*:

```bash
./mvnw package -Pcross-linux-x86_64                      # +6 slots for linux-x86_64
./mvnw package -Pcross-linux-x86_64,cross-linux-aarch64  # both Linux architectures
```

`build-cross.sh` does the work: fetch pdfium for the platform → `cargo zigbuild --release
--target <triple>.2.17` (pins glibc 2.17 for broad compatibility) → `stage-natives.sh`. At run
time `NativeLibraryLoader.platform()` picks `native/linux-x86_64/` or `native/linux-aarch64/`
automatically. Requirements on the target: glibc >= 2.17 and `libgcc_s` (any Debian/Ubuntu/RHEL
family image); **Alpine/musl is not supported** by the prebuilt pdfium. See `../../Dockerfile`.

Each bundled platform adds `slots x ~9 MB` to the jar; drop the profiles you do not deploy to.

## Upgrading pdfium-render

When bumping the `pdfium-render` version, check which `pdfium_XXXX` feature its
`pdfium_latest` maps to and update `PDFIUM_BUILD` in `fetch-pdfium.sh` (then delete `lib/`).
A mismatch shows up at startup as `dlsym(..., FPDF_...): symbol not found`.
