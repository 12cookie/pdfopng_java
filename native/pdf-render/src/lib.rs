//! # pdf-render: a C-ABI shim around pdfium for the Java side
//!
//! This crate is compiled as a `cdylib` (see `Cargo.toml`): a shared library
//! (`libpdf_render.dylib` / `.so`) that exposes plain C functions. Java loads it with the
//! FFM API (`java.lang.foreign`) and calls those functions directly - see
//! `com.example.demo.pdf.PdfRenderNative`. Nothing Rust-specific crosses the boundary:
//! Java only ever sees integers, pointers and byte buffers.
//!
//! ## Why a C ABI?
//!
//! Rust and Java cannot call each other natively; both, however, can speak "C":
//! plain functions with fixed-size integer/pointer arguments and a stable calling
//! convention. So every exported function here is declared `extern "C"` (use the C
//! calling convention) and `#[no_mangle]` (keep the function name as-is in the
//! binary, instead of Rust's mangled `_ZN10pdf_render...` form, so Java can find it
//! by name with `SymbolLookup.find("pdfrender_init")`).
//!
//! ## Conventions shared with the Java side
//!
//! * Every function returns an `i32` status: `0` = success, negative = one of the
//!   `ERR_*` codes below. Java mirrors these in `PdfRenderException`.
//! * On failure a human-readable message is stored in a *thread-local* string and
//!   can be fetched with [`pdfrender_last_error`]. It is thread-local so concurrent
//!   callers on different threads never see each other's messages.
//! * Input buffers (`data`, `len`) are owned by Java; we only read them for the
//!   duration of the call and never keep a pointer to them.
//! * Output buffers (`out_ptr`, `out_len`) are allocated by Rust and *ownership
//!   passes to Java*, which must hand them back via [`pdfrender_free`] so Rust can
//!   release them with the same allocator that created them. Never free memory in
//!   a different language/allocator than the one that allocated it.
//!
//! ## Why `unsafe`?
//!
//! Rust normally guarantees memory safety at compile time. Raw pointers coming from
//! another language carry no such guarantees, so touching them requires an `unsafe`
//! block/function - a promise from us that the caller (Java) upheld the contract
//! documented in each function's `# Safety` section (valid pointers, correct lengths).
//!
//! ## One library = one renderer
//!
//! pdfium keeps process-wide global state and must not be called from two threads at
//! once. This library therefore holds a single global [`Pdfium`] instance. To get N-way
//! concurrency, Java loads N *distinct file copies* of this library (and of libpdfium),
//! each of which gets its own private globals. See `NativeLibraryLoader` on the Java side.

use std::cell::RefCell;
use std::ffi::CStr;
use std::io::Cursor;
use std::os::raw::c_char;
use std::path::PathBuf;
use std::ptr;
use std::sync::OnceLock;

use image::ImageFormat;
use pdfium_render::prelude::*;

// ---------------------------------------------------------------------------------------
// Status codes. `pub const` = a public compile-time constant. `i32` = 32-bit signed int,
// matching Java's `int` / C's `int32_t`.
// ---------------------------------------------------------------------------------------

/// Success.
pub const OK: i32 = 0;
/// [`pdfrender_init`] has not been called (or failed) on this library instance.
pub const ERR_NOT_INITIALISED: i32 = -1;
/// A null pointer or otherwise invalid argument was passed.
pub const ERR_INVALID_ARGUMENT: i32 = -2;
/// pdfium could not parse the bytes as a PDF (corrupt, encrypted, not a PDF, ...).
pub const ERR_LOAD_DOCUMENT: i32 = -3;
/// The requested page index is >= the document's page count.
pub const ERR_PAGE_OUT_OF_RANGE: i32 = -4;
/// pdfium failed while rasterising the page.
pub const ERR_RENDER: i32 = -5;
/// The rendered bitmap could not be encoded as PNG.
pub const ERR_ENCODE: i32 = -6;
/// The libpdfium shared library could not be found or loaded.
pub const ERR_PDFIUM_NOT_FOUND: i32 = -7;

// ---------------------------------------------------------------------------------------
// Global state.
// ---------------------------------------------------------------------------------------

/// The one pdfium instance owned by this library copy.
///
/// `static` = a global that lives for the whole process. `OnceLock<T>` is a container
/// that starts empty and can be filled exactly once, safely even if several threads race
/// to fill it; afterwards everyone reads the same value. That is exactly the "initialise
/// once, then reuse" lifecycle we want for pdfium.
///
/// `Pdfium` is safe to share between threads here because this crate enables
/// pdfium-render's `thread_safe` feature, which wraps every pdfium call in a mutex.
/// That mutex is per library copy, which is why N copies give N-way parallelism while a
/// single copy would serialise everything.
static PDFIUM: OnceLock<Pdfium> = OnceLock::new();

thread_local! {
    /// Last error message raised *on the current thread*.
    ///
    /// `thread_local!` gives every OS thread its own independent copy of the variable.
    /// `RefCell<String>` is Rust's way of allowing mutation of a value that is otherwise
    /// shared (`borrow_mut()` checks at run time that nobody else is reading it).
    /// `const { .. }` lets the initial value be computed at compile time (cheaper).
    static LAST_ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

/// Records `message` as the current thread's last error and returns `code`, so call
/// sites can write `return fail(ERR_X, "why")`.
///
/// `impl Into<String>` means "accept anything that can be turned into a `String`",
/// so callers can pass either a `&str` literal or an owned `String` from `format!`.
fn fail(code: i32, message: impl Into<String>) -> i32 {
    // `with` gives temporary access to this thread's copy of LAST_ERROR.
    // `|e| ...` is a closure (anonymous function) receiving that reference.
    LAST_ERROR.with(|e| *e.borrow_mut() = message.into());
    code
}

/// Returns the initialised pdfium instance, or the error code to hand back to Java.
///
/// `Result<T, E>` is Rust's "either a value or an error" type. Here the error is simply
/// the `i32` status code we want to return. `&'static Pdfium` is a reference that is
/// valid for the rest of the process - fine, because the value lives in a `static`.
fn pdfium() -> Result<&'static Pdfium, i32> {
    PDFIUM
        .get() // Option<&Pdfium>: Some(..) once initialised, None before
        .ok_or_else(|| fail(ERR_NOT_INITIALISED, "pdfrender_init has not been called"))
}

// ---------------------------------------------------------------------------------------
// Locating and loading libpdfium.
// ---------------------------------------------------------------------------------------

/// Finds and `dlopen`s the pdfium shared library, returning the function bindings that
/// `pdfium-render` uses to talk to it.
///
/// pdfium-render does not link pdfium at build time; it loads it dynamically at run time,
/// so we must tell it where the binary is. Candidates are tried in order:
///
/// 1. `pdfium_path` passed in from Java (normally the per-slot copy the build bundled),
///    which may be either the library file itself or a directory containing it;
/// 2. the `PDFIUM_LIBRARY_PATH` environment variable;
/// 3. well-known install directories;
/// 4. finally the system loader's default search path (`bind_to_system_library`).
///
/// Returns the full error history on failure so a mismatch (e.g. an old libpdfium that
/// lacks a symbol this pdfium-render version needs) is diagnosable from the Java log.
fn resolve_bindings(pdfium_path: Option<PathBuf>) -> Result<Box<dyn PdfiumLibraryBindings>, String> {
    // `Vec<T>` is a growable array, like Java's ArrayList. `mut` = mutable (Rust
    // variables are immutable unless declared `mut`).
    let mut candidates: Vec<PathBuf> = Vec::new();

    // `if let Some(p) = ...` runs the block only when the Option holds a value, binding it to `p`.
    if let Some(p) = pdfium_path {
        candidates.push(p);
    }

    if let Ok(p) = std::env::var("PDFIUM_LIBRARY_PATH") {
        candidates.push(PathBuf::from(p));
    }

    candidates.push(PathBuf::from("/opt/homebrew/lib"));
    candidates.push(PathBuf::from("/usr/local/lib"));
    candidates.push(PathBuf::from("/usr/lib"));

    let mut tried = Vec::new();
    for candidate in candidates {
        // A directory becomes "<dir>/libpdfium.dylib" (platform-specific name); a file is used as-is.
        let path = if candidate.is_dir() {
            Pdfium::pdfium_platform_library_name_at_path(&candidate)
        } else {
            candidate
        };
        // `match` is like a switch over the Result's two shapes.
        match Pdfium::bind_to_library(&path) {
            Ok(b) => return Ok(b),
            Err(e) => tried.push(format!("{} ({e:?})", path.display())),
        }
    }

    // `.map_err(|e| ...)` transforms the error side of a Result, leaving Ok untouched.
    Pdfium::bind_to_system_library().map_err(|e| {
        format!(
            "could not load pdfium: {e}. Tried: {}. Pass an explicit path or set PDFIUM_LIBRARY_PATH.",
            tried.join(", ")
        )
    })
}

// ---------------------------------------------------------------------------------------
// Exported C functions. These five are the entire surface Java sees.
// ---------------------------------------------------------------------------------------

/// Initialises pdfium for this library instance. Idempotent: extra calls are no-ops.
///
/// C signature: `int32_t pdfrender_init(const char* pdfium_path);`
///
/// `pdfium_path` is a NUL-terminated C string (what Java produces with
/// `arena.allocateFrom(String)`), or `NULL` to fall back to the search in
/// `resolve_bindings` below.
///
/// # Safety
/// `pdfium_path` must be `NULL` or point to a valid, NUL-terminated UTF-8 string that
/// stays alive for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn pdfrender_init(pdfium_path: *const c_char) -> i32 {
    if PDFIUM.get().is_some() {
        return OK;
    }

    // Convert the raw C string into a Rust `Option<PathBuf>`.
    let path = if pdfium_path.is_null() {
        None
    } else {
        // `CStr::from_ptr` wraps the raw pointer (reads up to the NUL terminator);
        // `.to_str()` validates that the bytes are UTF-8.
        match CStr::from_ptr(pdfium_path).to_str() {
            Ok(s) if !s.is_empty() => Some(PathBuf::from(s)),
            Ok(_) => None, // empty string: treat like NULL
            Err(_) => return fail(ERR_INVALID_ARGUMENT, "pdfium_path is not valid UTF-8"),
        }
    };

    match resolve_bindings(path) {
        Ok(bindings) => {
            // `set` fails only if another thread initialised first, which is fine:
            // either way PDFIUM now holds a value. `let _ =` discards the result.
            let _ = PDFIUM.set(Pdfium::new(bindings));
            OK
        }

        Err(msg) => fail(ERR_PDFIUM_NOT_FOUND, msg),
    }
}

/// Writes the page count of the PDF in `data[0..len]` to `*out_count`.
///
/// C signature: `int32_t pdfrender_page_count(const uint8_t* data, size_t len, uint32_t* out_count);`
///
/// `*const u8` = pointer to read-only bytes; `usize` = pointer-sized unsigned integer
/// (C's `size_t`, Java passes a `long`); `*mut u32` = pointer to a writable 32-bit int
/// that we fill in (an "out-parameter", the C idiom for returning more than one value).
///
/// # Safety
/// `data` must point to `len` readable bytes and `out_count` to a writable `u32`.
#[no_mangle]
pub unsafe extern "C" fn pdfrender_page_count(data: *const u8, len: usize, out_count: *mut u32) -> i32 {
    if data.is_null() || out_count.is_null() {
        return fail(ERR_INVALID_ARGUMENT, "null pointer argument");
    }

    // `match` + early return turns the Err(code) into our i32 return value.
    let pdfium = match pdfium() {
        Ok(p) => p,
        Err(code) => return code,
    };

    // View Java's bytes as a Rust slice (`&[u8]`) *without copying*. The slice is only
    // valid while Java keeps the memory alive, i.e. for this call - which is all we need.
    let bytes = std::slice::from_raw_parts(data, len);

    match pdfium.load_pdf_from_byte_slice(bytes, None) {
        Ok(doc) => {
            // `*out_count = ..` writes through the pointer.
            *out_count = doc.pages().len() as u32;
            OK
        }

        // `{e}` inside `format!` interpolates the error's Display text.
        Err(e) => fail(ERR_LOAD_DOCUMENT, format!("failed to load PDF: {e}")),
    }
}

/// Renders page `page_index` (0-based) of the PDF in `data[0..len]` to PNG.
///
/// C signature:
/// ```c
/// int32_t pdfrender_render_page_png(const uint8_t* data, size_t len, uint32_t page_index,
///                                   uint32_t dpi, uint8_t** out_ptr, size_t* out_len);
/// ```
///
/// `dpi == 0` selects 150 DPI. On success `*out_ptr`/`*out_len` describe a PNG byte
/// buffer *owned by the caller*, who must release it with [`pdfrender_free`]. On failure
/// both are set to null/0 so a careless caller cannot free garbage.
///
/// # Safety
/// `data` must point to `len` readable bytes; `out_ptr` and `out_len` must be writable.
#[no_mangle]
pub unsafe extern "C" fn pdfrender_render_page_png(
    data: *const u8,
    len: usize,
    page_index: u32,
    dpi: u32,
    out_ptr: *mut *mut u8, // pointer to a pointer: where we store the buffer address
    out_len: *mut usize,   // where we store the buffer length
) -> i32 {
    if data.is_null() || out_ptr.is_null() || out_len.is_null() {
        return fail(ERR_INVALID_ARGUMENT, "null pointer argument");
    }
    *out_ptr = ptr::null_mut();
    *out_len = 0;

    let pdfium = match pdfium() {
        Ok(p) => p,
        Err(code) => return code,
    };

    let bytes = std::slice::from_raw_parts(data, len);

    match render_page(pdfium, bytes, page_index, dpi) {
        Ok(png) => {
            // Hand the Vec's heap buffer to Java:
            //  1. `into_boxed_slice()` shrinks it so capacity == length (we only pass a
            //     length back, so the two must match for `pdfrender_free` to rebuild it);
            //  2. record its address and length in the out-params;
            //  3. `mem::forget` tells Rust *not* to free the buffer when `boxed` goes out
            //     of scope. Ownership now lives with Java until `pdfrender_free`.
            let mut boxed = png.into_boxed_slice();
            *out_len = boxed.len();
            *out_ptr = boxed.as_mut_ptr();
            std::mem::forget(boxed);
            OK
        }

        Err((code, msg)) => fail(code, msg),
    }
}

/// The actual rendering, in safe Rust. Kept separate from the `extern "C"` wrapper so
/// the pointer juggling and the PDF logic do not get mixed together.
///
/// Returns `Ok(png_bytes)` or `Err((status_code, message))`. `(i32, String)` is a tuple.
fn render_page(pdfium: &Pdfium, bytes: &[u8], page_index: u32, dpi: u32) -> Result<Vec<u8>, (i32, String)> {

    // The `?` operator: if the expression is Err, return that Err from this function
    // immediately; if Ok, unwrap the value. It replaces a lot of explicit `match`es.
    let document = pdfium
        .load_pdf_from_byte_slice(bytes, None)
        .map_err(|e| (ERR_LOAD_DOCUMENT, format!("failed to load PDF: {e}")))?;

    let page_count = document.pages().len() as u32;
    if page_index >= page_count {
        return Err((
            ERR_PAGE_OUT_OF_RANGE,
            format!("page index {page_index} out of range (document has {page_count} pages)"),
        ));
    }

    let page = document
        .pages()
        .get(page_index as PdfPageIndex)
        .map_err(|e| (ERR_RENDER, format!("failed to open page {page_index}: {e}")))?;

    // PDF user space is 72 points per inch, so scale = dpi / 72.
    let dpi = if dpi == 0 { 150 } else { dpi };
    let config = PdfRenderConfig::new()
        .scale_page_by_factor(dpi as f32 / 72.0)
        .render_form_data(true) // draw filled-in form fields
        .render_annotations(true); // draw annotations (highlights, stamps, ...)

    // Rasterise -> pdfium bitmap -> `image` crate DynamicImage -> 8-bit RGB (pages are
    // drawn on an opaque white background, so an alpha channel would only add bytes).
    let image = page
        .render_with_config(&config)
        .map_err(|e| (ERR_RENDER, format!("failed to render page {page_index}: {e}")))?
        .as_image()
        .map_err(|e| (ERR_RENDER, format!("failed to read bitmap for page {page_index}: {e}")))?
        .into_rgb8();

    // Encode to PNG into an in-memory Vec. `Cursor` makes a Vec look like a writable file.
    let mut png = Vec::new();
    image
        .write_to(&mut Cursor::new(&mut png), ImageFormat::Png)
        .map_err(|e| (ERR_ENCODE, format!("failed to encode PNG: {e}")))?;
    Ok(png)
}

/// Releases a buffer previously returned by [`pdfrender_render_page_png`].
///
/// C signature: `void pdfrender_free(uint8_t* ptr, size_t len);`
///
/// This reverses the `mem::forget` above: `Box::from_raw` re-adopts the memory so that
/// `drop` frees it through Rust's allocator. `len` must be the exact length we handed
/// out, because that is how Rust knows the size of the allocation.
///
/// # Safety
/// `ptr`/`len` must be exactly one pair returned by this library, passed exactly once,
/// and never used again afterwards (a double free or use-after-free is undefined behaviour).
#[no_mangle]
pub unsafe extern "C" fn pdfrender_free(ptr: *mut u8, len: usize) {
    if !ptr.is_null() {
        drop(Box::from_raw(std::slice::from_raw_parts_mut(ptr, len)));
    }
}

/// Copies the calling thread's last error message into `buf` and returns the number of
/// bytes written (UTF-8, *not* NUL-terminated, truncated to `cap`).
///
/// C signature: `size_t pdfrender_last_error(uint8_t* buf, size_t cap);`
///
/// Java calls this right after a non-zero status, on the same thread, to build the
/// exception message. The caller supplies the buffer so no allocation crosses the
/// boundary in the error path.
///
/// # Safety
/// `buf` must point to at least `cap` writable bytes.
#[no_mangle]
pub unsafe extern "C" fn pdfrender_last_error(buf: *mut u8, cap: usize) -> usize {
    if buf.is_null() || cap == 0 {
        return 0;
    }

    LAST_ERROR.with(|e| {
        let msg = e.borrow();
        let n = msg.len().min(cap);
        // memcpy `n` bytes from the String into the caller's buffer.
        ptr::copy_nonoverlapping(msg.as_ptr(), buf, n);
        n
    })
}
