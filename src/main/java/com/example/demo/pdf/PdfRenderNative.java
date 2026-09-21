package com.example.demo.pdf;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Binding to one loaded copy of the Rust {@code libpdf_render} shared library, using the
 * Java Foreign Function &amp; Memory API (FFM, {@code java.lang.foreign}, final since JDK 22).
 *
 * <h2>How calling Rust from Java works here</h2>
 *
 * FFM lets Java call any function that follows the C calling convention, without writing
 * any C/JNI glue. The Rust side exports such functions (see {@code native/pdf-render/src/lib.rs}:
 * {@code #[no_mangle] pub unsafe extern "C" fn ...}). On the Java side, for each function we:
 * <ol>
 *   <li><b>Load the library</b> - {@link SymbolLookup#libraryLookup(Path, Arena)} is a
 *       {@code dlopen}: it maps the file into the process and lets us look up exported symbols.</li>
 *   <li><b>Find the symbol</b> - {@code lookup.find("pdfrender_init")} returns the function's
 *       address as a {@link MemorySegment}.</li>
 *   <li><b>Describe its signature</b> - a {@link FunctionDescriptor} lists the C parameter and
 *       return types using {@code ValueLayout}s ({@code JAVA_INT} = {@code int32_t},
 *       {@code JAVA_LONG} = {@code int64_t}/{@code size_t} on 64-bit, {@code ADDRESS} = any pointer).
 *       This must match the Rust declaration exactly; FFM cannot check it for us.</li>
 *   <li><b>Create a downcall handle</b> - {@link Linker#downcallHandle} turns address + descriptor
 *       into a {@link MethodHandle}. Invoking it <em>is</em> the native call ("downcall" = Java
 *       calling down into native code; the reverse is an "upcall").</li>
 * </ol>
 *
 * <h2>Memory</h2>
 *
 * Native code cannot read Java heap objects such as {@code byte[]} (the GC may move them), so
 * anything passed by pointer must first live in <em>off-heap</em> memory. An {@link Arena}
 * allocates such memory and frees all of it when closed; {@code Arena.ofConfined()} is the
 * cheapest kind, usable from one thread and released by try-with-resources. Everything we
 * allocate per call (the PDF bytes, out-parameters, string paths) lives in a confined arena
 * scoped to that call.
 * <p>
 * Memory allocated by <em>Rust</em> (the PNG result) is different: Rust owns it, and we must give
 * it back with {@code pdfrender_free} after copying it into a Java {@code byte[]}. Freeing it
 * from Java with anything else would corrupt Rust's allocator.
 *
 * <h2>Error handling</h2>
 *
 * C has no exceptions. Every Rust function returns {@code 0} on success or a negative code;
 * the message is fetched separately via {@code pdfrender_last_error}. {@link #check(int)}
 * turns that convention back into a {@link PdfRenderException}.
 *
 * <h2>Threading</h2>
 *
 * One instance of this class wraps one library copy and therefore one pdfium instance, which
 * must not be used from two threads at once. {@link PdfRenderService} enforces that by handing
 * instances out through a pool; nothing in this class itself is synchronized.
 *
 * <h2>Native access</h2>
 *
 * {@code libraryLookup} and {@code downcallHandle} are "restricted" methods: the JVM warns (or,
 * with {@code --illegal-native-access=deny}, refuses) unless native access is enabled. This
 * project enables it via {@code --enable-native-access=ALL-UNNAMED} for tests/{@code spring-boot:run}
 * and the {@code Enable-Native-Access} manifest attribute for {@code java -jar}.
 */
final class PdfRenderNative {

    /**
     * The platform linker knows the C calling convention (ABI) of the current OS/CPU:
     * which registers hold arguments, how the stack is laid out, and so on.
     */
    private static final Linker LINKER = Linker.nativeLinker();

    /** Capacity of the scratch buffer we let Rust copy error messages into. */
    private static final int ERROR_BUFFER_SIZE = 4096;

    // One MethodHandle per exported Rust function. Invoking a handle performs the native call.
    private final MethodHandle init;
    private final MethodHandle pageCount;
    private final MethodHandle renderPagePng;
    private final MethodHandle free;
    private final MethodHandle lastError;

    /**
     * Loads the shared library at {@code libraryPath} and resolves its five functions.
     *
     * @param libraryPath path to a {@code libpdf_render.dylib}/{@code .so}. Each <em>distinct file</em>
     *                    is loaded as a separate image with separate globals; loading the same path
     *                    twice would return the same image (see {@link NativeLibraryLoader}).
     */
    PdfRenderNative(Path libraryPath) {
        // Arena.global() = memory/handles that live as long as the JVM. We never unload the
        // library, so the global arena is the right owner for the lookup.
        SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, Arena.global());

        // Each FunctionDescriptor is the Java-side declaration of the C prototype:
        //   FunctionDescriptor.of(<return layout>, <param layouts...>)
        //   FunctionDescriptor.ofVoid(<param layouts...>)          for void functions

        // int32_t pdfrender_init(const char* pdfium_path)
        init = downcall(lookup, "pdfrender_init",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        // int32_t pdfrender_page_count(const uint8_t* data, size_t len, uint32_t* out_count)
        pageCount = downcall(lookup, "pdfrender_page_count",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));

        // int32_t pdfrender_render_page_png(const uint8_t* data, size_t len, uint32_t page_index,
        //                                   uint32_t dpi, uint8_t** out_ptr, size_t* out_len)
        renderPagePng = downcall(lookup, "pdfrender_render_page_png",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

        // void pdfrender_free(uint8_t* ptr, size_t len)
        free = downcall(lookup, "pdfrender_free",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));

        // size_t pdfrender_last_error(uint8_t* buf, size_t cap)
        lastError = downcall(lookup, "pdfrender_last_error",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
    }

    /** Symbol address + signature → callable {@link MethodHandle}. */
    private static MethodHandle downcall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        MemorySegment address = lookup.find(symbol)
                .orElseThrow(() -> new IllegalStateException("Symbol not found in pdf-render library: " + symbol));
        return LINKER.downcallHandle(address, descriptor);
    }

    /**
     * Calls {@code pdfrender_init}, which loads libpdfium into this library copy.
     *
     * @param pdfiumPath the libpdfium file or directory to use, or {@code null} to let the Rust side
     *                   search well-known locations
     */
    void init(Path pdfiumPath) {
        try (Arena arena = Arena.ofConfined()) {
            // Rust expects a NUL-terminated C string. allocateFrom(String) encodes the text
            // as UTF-8 off-heap and appends the terminating 0 byte. MemorySegment.NULL is C NULL.
            MemorySegment path = pdfiumPath == null
                    ? MemorySegment.NULL
                    : arena.allocateFrom(pdfiumPath.toString());

            // invokeExact requires the argument and return types to match the descriptor
            // *exactly*: (MemorySegment) -> int here. The cast to int is part of that contract.
            check((int) init.invokeExact(path));
        } catch (Throwable t) {
            // MethodHandle.invokeExact is declared to throw Throwable; unwrap it sensibly.
            throw rethrow(t);
        }
    }

    /** Calls {@code pdfrender_page_count}. */
    int pageCount(byte[] pdf) {
        try (Arena arena = Arena.ofConfined()) {
            // Copy the PDF bytes off-heap so Rust can read them through a stable pointer.
            MemorySegment data = arena.allocateFrom(JAVA_BYTE, pdf);
            // Out-parameter: 4 bytes Rust will write the page count into.
            MemorySegment outCount = arena.allocate(JAVA_INT);

            // Java long ↔ C size_t (both 64-bit on the platforms we support).
            check((int) pageCount.invokeExact(data, (long) pdf.length, outCount));

            // Read the value Rust wrote at offset 0 of the out-parameter.
            return outCount.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Calls {@code pdfrender_render_page_png} and copies the resulting PNG into a Java array.
     *
     * @param pageIndex zero-based page index
     * @param dpi       output resolution; {@code 0} lets Rust pick its default (150)
     */
    byte[] renderPagePng(byte[] pdf, int pageIndex, int dpi) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(JAVA_BYTE, pdf);
            // Two out-parameters: a slot for a pointer (uint8_t**) and a slot for a size_t.
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment outLen = arena.allocate(JAVA_LONG);

            check((int) renderPagePng.invokeExact(data, (long) pdf.length, pageIndex, dpi, outPtr, outLen));

            // Rust has now stored the buffer address and length in the two out-parameters.
            MemorySegment ptr = outPtr.get(ADDRESS, 0);
            long len = outLen.get(JAVA_LONG, 0);
            try {
                // A pointer read from native memory has an unknown size (0 bytes as far as
                // Java knows), so any access would throw. reinterpret(len) tells Java "trust
                // me, this points at len bytes"; toArray then copies them onto the Java heap.
                return ptr.reinterpret(len).toArray(JAVA_BYTE);
            } finally {
                // Give the buffer back to Rust's allocator - always, even if the copy failed.
                free.invokeExact(ptr, len);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Converts the C status-code convention into an exception carrying Rust's message. */
    private void check(int code) throws Throwable {
        if (code != 0) {
            throw new PdfRenderException(code, lastError());
        }
    }

    /**
     * Fetches the last error message Rust recorded <em>on this thread</em>. Must be called on
     * the same thread as the failing call (the message is stored thread-locally in Rust).
     */
    private String lastError() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(ERROR_BUFFER_SIZE);
            long n = (long) lastError.invokeExact(buf, (long) ERROR_BUFFER_SIZE);
            // Rust wrote n UTF-8 bytes (no NUL terminator); decode just that prefix.
            return new String(buf.asSlice(0, n).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code MethodHandle.invokeExact} is declared {@code throws Throwable}. In practice only our
     * own {@link PdfRenderException} (from {@link #check}) or JVM errors can come out of it, so
     * pass those through and wrap anything unexpected.
     */
    private static RuntimeException rethrow(Throwable t) {
        return switch (t) {
            case RuntimeException e -> e;
            case Error e -> throw e;
            default -> new PdfRenderException("Native call into pdf-render failed", t);
        };
    }
}
