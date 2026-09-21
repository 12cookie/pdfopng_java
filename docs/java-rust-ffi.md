# How the Java ↔ Rust PDF rendering works

A guided tour for someone new to both calling native code from Java and to Rust. It follows a
single request end to end and points at the code that implements each step. The code itself is
heavily commented; this document is the map.

## The pieces

```
HTTP multipart upload
        │
        ▼
PdfRenderController        (Java)  parse request, 1-based → 0-based page, shape response
        │
        ▼
PdfRenderService           (Java)  pool of N renderers; BlockingQueue = semaphore
        │  acquire one renderer
        ▼
PdfRenderNative            (Java)  FFM binding: MethodHandles for 5 C functions
        │  downcall (native call)
        ▼
libpdf_render.dylib        (Rust)  extern "C" shim: pointers → safe Rust → pdfium-render crate
        │  dlopen'ed at init
        ▼
libpdfium.dylib            (C++)   Google's PDF engine, prebuilt binary
```

Plus, at build time: `NativeLibraryLoader` (Java) finds the libraries, and the Maven build
(`pom.xml` + `native/pdf-render/*.sh`) compiles and bundles them.

## Vocabulary

| Term                                                             | Meaning                                                                                                                                                      |
|------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Shared library** (`.dylib` macOS, `.so` Linux, `.dll` Windows) | A file of compiled machine code that a running process can load and call.                                                                                    |
| **`dlopen`**                                                     | The OS call that loads a shared library into the process. Same file → same already-loaded copy; distinct files → distinct copies.                            |
| **Symbol**                                                       | A named function (or variable) exported by a shared library, e.g. `pdfrender_init`.                                                                          |
| **C ABI / calling convention**                                   | The rules for how arguments and return values are passed in registers/stack. Both Rust and Java can produce/consume it, which is why C is the lingua franca. |
| **`extern "C"` / `#[no_mangle]`** (Rust)                         | "Use the C calling convention" / "export this function under its plain name" so other languages can find and call it.                                        |
| **`cdylib`** (Rust)                                              | Cargo crate type that produces a shared library exposing only the `extern "C"` functions.                                                                    |
| **FFM** (Java)                                                   | Foreign Function & Memory API, `java.lang.foreign`. Lets Java call C functions and manage off-heap memory. Replaces JNI, needs no C glue code.               |
| **Downcall**                                                     | Java calling native code. (An *upcall* is native code calling back into Java - not used here.)                                                               |
| **`MemorySegment`** (Java)                                       | A pointer plus a size: a typed view of off-heap (or on-heap) memory.                                                                                         |
| **`Arena`** (Java)                                               | Allocates off-heap memory and frees all of it at once when closed. `Arena.ofConfined()` = single-threaded, freed by try-with-resources.                      |
| **Out-parameter**                                                | C idiom for returning a second value: the caller passes a pointer, the callee writes through it.                                                             |
| **`unsafe`** (Rust)                                              | Marks code where the compiler can't prove memory safety - anything touching raw pointers from another language.                                              |

## Following one request: `POST /api/pdf/png?page=1&dpi=150`

### 1. Controller → Service (Java only)

`PdfRenderController.renderPage` reads the upload into a `byte[]`, converts `page=1` to index
`0`, and calls `PdfRenderService.renderPage(pdf, 0, 150)`.

### 2. Service: acquire a renderer

`PdfRenderService.withRenderer` takes one `PdfRenderNative` out of the `BlockingQueue`. If all N
are busy the thread waits up to `pdf.render.acquire-timeout`, then throws `ERR_BUSY` → HTTP 503.
Whatever happens in the native call, the `finally` puts the renderer back. This is the semaphore.

### 3. `PdfRenderNative.renderPagePng`: crossing into Rust

```java
int fun(String[] args) {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment data   = arena.allocateFrom(JAVA_BYTE, pdf); // copy PDF bytes off-heap
        MemorySegment outPtr = arena.allocate(ADDRESS);            // uint8_t**  (Rust writes here)
        MemorySegment outLen = arena.allocate(JAVA_LONG);          // size_t*    (Rust writes here)

        check((int) renderPagePng.invokeExact(data, (long) pdf.length, pageIndex, dpi, outPtr, outLen));

        MemorySegment ptr = outPtr.get(ADDRESS, 0);
        long len = outLen.get(JAVA_LONG, 0);
        try {
            return ptr.reinterpret(len).toArray(JAVA_BYTE);  // copy PNG onto the Java heap
        } finally {
            free.invokeExact(ptr, len);                       // give Rust its buffer back
        }
    }
}
```

* The `byte[]` is copied off-heap because native code must never see Java heap memory (the
  garbage collector moves objects around).
* `invokeExact` runs the native function. Its Java signature must match the `FunctionDescriptor`
  declared in the constructor *exactly* - that's why the `(int)` cast and `(long)` conversions
  are there.
* Rust cannot return a variable-length array directly, so it returns a pointer + length via
  out-parameters, and Java must call `pdfrender_free` afterward. Memory is always freed by the
  side that allocated it.

The `FunctionDescriptor` is the contract between the two languages:

```java
int fun(MemorySegment data, long len, int pageIndex, int dpi, MemorySegment outPtr, MemorySegment outLen) {
    // Rust:  fn pdfrender_render_page_png(data: *const u8, len: usize, page_index: u32,
    //                                     dpi: u32, out_ptr: *mut *mut u8, out_len: *mut usize) -> i32
    FunctionDescriptor.of(JAVA_INT,  ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    //                    ^return    ^data    ^len       ^page     ^dpi      ^out_ptr ^out_len
}
```

Nothing verifies this at compile time. If the two drift apart you get garbage or a crash, so
each Rust function's doc comment states its C signature and each Java descriptor has the same
prototype in a comment right above it.

### 4. Rust: `pdfrender_render_page_png` in `native/pdf-render/src/lib.rs`

1. Null-check the pointers, reset the out-parameters.
2. Fetch the global `Pdfium` (`OnceLock`), error `ERR_NOT_INITIALISED` if `pdfrender_init`
   wasn't called.
3. `std::slice::from_raw_parts(data, len)` - view Java's bytes as a Rust slice, no copy.
4. Call the *safe* helper `render_page`, which does all the real work with the
   `pdfium-render` crate: load document → bounds-check page → render at `dpi/72` scale →
   convert to RGB8 → PNG-encode into a `Vec<u8>`.
5. Hand the `Vec`'s buffer to Java: `into_boxed_slice()`, store pointer + length in the
   out-parameters, and `std::mem::forget` so Rust does *not* free it when the function returns.
6. Return `0`. On any error: store the message thread-locally (`fail(...)`) and return the code.

`pdfrender_free` later does the reverse (`Box::from_raw` + `drop`) to release the buffer.

### 5. Errors: how a Rust `Err` becomes a Java exception

Rust: `fail(ERR_PAGE_OUT_OF_RANGE, "page index 98 out of range ...")` stores the message in a
`thread_local!` string and returns `-4`.
Java: `check(-4)` sees non-zero, calls `pdfrender_last_error` (on the same thread - that's
why it's thread-local) to read the message, and throws `PdfRenderException(-4, message)`.
The controller's `@ExceptionHandler` maps code -4 to HTTP 400.

## Rust concepts you'll meet in `lib.rs`

* **Ownership**: every value has one owner; when the owner goes out of scope the value is freed.
  `mem::forget` opts out of that for the PNG buffer so Java can own it for a while.
* **`Result<T, E>` and `?`**: functions return `Ok(value)` or `Err(error)`. `expr?` means "if
  this is `Err`, return it from the current function now; otherwise unwrap the `Ok`".
* **`Option<T>`**: `Some(value)` or `None` - Rust's null, but the compiler forces you to handle
  the `None` case. `if let Some(p) = x { ... }` runs only when there is a value.
* **`match`**: exhaustive switch over the possible shapes of a value.
* **Closures `|x| ...`**: anonymous functions, used with `map_err`, `ok_or_else`, `with`.
* **`static` + `OnceLock`**: a global initialized exactly once, safe across threads.
* **`thread_local!`**: one independent copy of a variable per OS thread.
* **`unsafe`**: required to dereference raw pointers. The `# Safety` doc section on each
  function states what the caller (Java) must guarantee.

## Why N copies of the libraries?

pdfium has global state and is single-threaded. `libpdf_render` also has globals (the
`OnceLock<Pdfium>`, the mutex inside the `pdfium-render` crate). Because `dlopen` gives a
separate image - with separate globals - for every *distinct file*, loading N file copies yields N
fully independent renderers in one process. `NativeLibraryLoader` guarantees the files are
distinct (it copies them into per-slot temp directories), and both `libpdf_render` and
`libpdfium` are copied so the six shims don't all share one pdfium. `pdf.render.slots` in
`pom.xml` decides how many copies the jar carries; `pdf.render.concurrency` decides how many are
used at run time.

## Build pipeline (`pom.xml`)

| Phase                | Step                    | What it does                                                                                   |
|----------------------|-------------------------|------------------------------------------------------------------------------------------------|
| `generate-resources` | `fetch-pdfium.sh`       | Downloads the prebuilt `libpdfium` pinned to the build `pdfium-render` expects (7881), once.   |
| `generate-resources` | `cargo build --release` | Compiles the Rust crate to `libpdf_render.dylib`.                                              |
| `process-resources`  | `stage-natives.sh`      | Copies both libraries N times into `target/classes/native/<platform>/slot-i/`.                 |
| `package`            | Spring Boot repackage   | Everything ends up inside the jar; `Enable-Native-Access: ALL-UNNAMED` is set in the manifest. |

`-DskipNative` skips the first two steps when nothing in Rust changed.

Cross-compiling: `-Pcross-linux-x86_64` / `-Pcross-linux-aarch64` additionally run
`build-cross.sh`, which uses `cargo zigbuild` to produce a Linux `libpdf_render.so` from macOS and
stages it (with the Linux pdfium download) under `native/linux-*/`. The Java code needs no
change - `NativeLibraryLoader.platform()` selects the directory for the OS it is running on.

## Things that will bite you

* **Descriptor mismatch**: changing a Rust signature without updating the `FunctionDescriptor`
  (or vice versa) compiles fine and crashes at run time. Update both, and the comment.
* **Wrong pdfium build**: `pdfium-render` expects specific pdfium symbols. A mismatch shows up
  at startup as `dlsym(..., FPDF_xxx): symbol not found`. Keep `PDFIUM_BUILD` in
  `fetch-pdfium.sh` aligned with the crate version.
* **Freeing across languages**: never `free` Rust memory from Java or vice versa; always route
  through `pdfrender_free`.
* **Using a renderer from two threads**: nothing in `PdfRenderNative` is synchronized - only ever
  reach it through `PdfRenderService`.
* **Same path twice**: two `dlopen`s of one file share one image. Concurrency depends on the
  copies being distinct files.
