package com.example.demo.pdf;

import lombok.Getter;

/**
 * Raised when PDF rendering fails, either inside the native Rust/pdfium code or in the Java
 * pool around it.
 * <p>
 * C functions cannot throw, so the Rust side reports failures as negative integer status codes
 * (plus a thread-local message). {@code PdfRenderNative.check} converts those into this exception
 * with the same {@code code}, so callers can distinguish "bad input" from "renderer
 * broken" without parsing messages. The {@code ERR_*} constants must stay in sync with the
 * {@code pub const ERR_*} values in {@code native/pdf-render/src/lib.rs}; {@link #ERR_BUSY} is
 * the one code that exists only on the Java side.
 */
@Getter
public class PdfRenderException extends RuntimeException {

    // Error codes mirrored from native/pdf-render/src/lib.rs.

    /** {@code pdfrender_init} was never called on that library copy (a programming error). */
    public static final int ERR_NOT_INITIALISED = -1;
    /** A null pointer, a bad DPI, or another invalid argument. */
    public static final int ERR_INVALID_ARGUMENT = -2;
    /** pdfium could not parse the bytes as a PDF (corrupt, encrypted, not a PDF). */
    public static final int ERR_LOAD_DOCUMENT = -3;
    /** The requested page does not exist in the document. */
    public static final int ERR_PAGE_OUT_OF_RANGE = -4;
    /** pdfium failed while rasterizing the page. */
    public static final int ERR_RENDER = -5;
    /** The rendered bitmap could not be PNG-encoded. */
    public static final int ERR_ENCODE = -6;
    /** libpdfium could not be found/loaded, or is a build incompatible with pdfium-render. */
    public static final int ERR_PDFIUM_NOT_FOUND = -7;
    /** Java-side only: no renderer became free within the acquire timeout. */
    public static final int ERR_BUSY = -100;

    private final int code;

    /** For failures reported by the native side (or Java-side validation) with a specific code. */
    public PdfRenderException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** For unexpected failures of the FFM call itself (e.g. a {@code Throwable} out of {@code invokeExact}). */
    public PdfRenderException(String message, Throwable cause) {
        super(message, cause);
        this.code = ERR_RENDER;
    }

    /** True when every renderer in the pool was busy; the request may succeed if retried. */
    public boolean isBusy() {
        return code == ERR_BUSY;
    }

    /** True when the failure is caused by the supplied input rather than the renderer itself. */
    public boolean isClientError() {
        return code == ERR_LOAD_DOCUMENT || code == ERR_PAGE_OUT_OF_RANGE || code == ERR_INVALID_ARGUMENT;
    }
}
