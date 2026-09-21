package com.example.demo.pdf;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Renders PDF pages to PNG using pdfium through the Rust {@code pdf-render} library. This is the
 * class the rest of the application talks to; everything below it is plumbing.
 *
 * <h2>Concurrency model</h2>
 * <p>
 * pdfium is single-threaded, so one loaded library copy can only serve one caller at a time.
 * Instead of a lock around a single renderer (which would serialize every request), this service
 * owns a <em>pool</em> of N independent renderers, each backed by its own copy of the native
 * libraries (see {@link NativeLibraryLoader} for why copies give independence).
 * <p>
 * The pool is a bounded {@link BlockingQueue} of idle renderers, which doubles as the semaphore:
 * <ul>
 *   <li>taking a renderer out ({@link #acquire()}) is acquiring one of N permits - the caller
 *       blocks while the queue is empty, up to {@link PdfRenderProperties#acquireTimeout()};</li>
 *   <li>putting it back (in {@link #withRenderer}'s {@code finally}) releases the permit.</li>
 * </ul>
 * So at most N native calls are ever in flight, each on its own pdfium instance, and a renderer
 * is never used by two threads at once. Requests that cannot get a renderer in time fail with
 * {@link PdfRenderException#ERR_BUSY}, which the controller maps to HTTP 503.
 * <p>
 * Renderers are acquired per call (per page), not per document, so one large document cannot
 * monopolize a slot while other requests wait.
 *
 * <h2>Lifecycle</h2>
 * <p>
 * All native loading happens in the constructor, so a missing or mismatched library fails the
 * application at startup with a clear message rather than on the first request.
 */
@Service
@EnableConfigurationProperties(PdfRenderProperties.class)
public class PdfRenderService {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderService.class);

    /**
     * Idle renderers. Capacity == total renderers, so {@code add} in {@link #withRenderer} can never fail.
     */
    private final BlockingQueue<PdfRenderNative> pool;
    private final int poolSize;
    private final Duration acquireTimeout;
    private final PdfRenderProperties properties;

    public PdfRenderService(PdfRenderProperties properties) throws IOException {
        this.properties = properties;
        this.acquireTimeout = properties.acquireTimeout();

        List<NativeLibraryLoader.Slot> slots = NativeLibraryLoader.load(
                properties.libraryPath(), properties.pdfiumPath(), properties.concurrency());

        this.poolSize = slots.size();
        // fair = true: waiting threads get renderers in FIFO order instead of racing.
        this.pool = new ArrayBlockingQueue<>(poolSize, true);
        for (NativeLibraryLoader.Slot slot : slots) {
            // Each slot: dlopen its libpdf_render copy, then have it dlopen its libpdfium copy.
            PdfRenderNative renderer = new PdfRenderNative(slot.renderer());
            renderer.init(slot.pdfium());
            pool.add(renderer);
        }

        log.info("pdfium renderer pool ready: {} independent instance(s), acquire timeout {}", poolSize, acquireTimeout);
    }

    /**
     * Maximum number of renders that can run in parallel.
     */
    public int concurrency() {
        return poolSize;
    }

    /**
     * Renderers currently idle.
     */
    public int available() {
        return pool.size();
    }

    public int defaultDpi() {
        return properties.defaultDpi();
    }

    public int pageCount(byte[] pdf) {
        return withRenderer(r -> r.pageCount(pdf));
    }

    /**
     * Renders a single page.
     *
     * @param pdf       the PDF document bytes
     * @param pageIndex zero-based page index
     * @param dpi       output resolution; {@code 0} selects {@link PdfRenderProperties#defaultDpi()}
     * @return PNG-encoded image
     */
    public byte[] renderPage(byte[] pdf, int pageIndex, int dpi) {
        if (pageIndex < 0) {
            throw new PdfRenderException(PdfRenderException.ERR_PAGE_OUT_OF_RANGE, "page index must be >= 0");
        }

        int effectiveDpi = resolveDpi(dpi);
        return withRenderer(r -> r.renderPagePng(pdf, pageIndex, effectiveDpi));
    }

    /**
     * Renders every page, in order. A renderer is acquired per page rather than for the whole
     * document so one large document cannot monopolize a slot.
     */
    public List<byte[]> renderAllPages(byte[] pdf, int dpi) {
        int count = pageCount(pdf);
        int effectiveDpi = resolveDpi(dpi);
        List<byte[]> pages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int page = i;
            pages.add(withRenderer(r -> r.renderPagePng(pdf, page, effectiveDpi)));
        }

        return pages;
    }

    /**
     * Runs {@code action} with an exclusively-held renderer, then returns the renderer to the
     * pool whether the action succeeded or threw. Every native call goes through here.
     */
    private <T> T withRenderer(Function<PdfRenderNative, T> action) {
        PdfRenderNative renderer = acquire();
        try {
            return action.apply(renderer);
        } finally {
            pool.add(renderer);
        }
    }

    /**
     * Blocks until a renderer is free or the acquire timeout elapses.
     */
    private PdfRenderNative acquire() {
        try {
            PdfRenderNative renderer = pool.poll(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (renderer == null) {
                throw new PdfRenderException(PdfRenderException.ERR_BUSY,
                        "all " + poolSize + " PDF renderers busy for " + acquireTimeout + "; try again later");
            }

            return renderer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfRenderException(PdfRenderException.ERR_BUSY, "interrupted while waiting for a PDF renderer");
        }
    }

    /**
     * Applies the default for {@code 0} and enforces the configured upper bound.
     */
    private int resolveDpi(int dpi) {
        if (dpi == 0) {
            return properties.defaultDpi();
        }

        if (dpi < 1 || dpi > properties.maxDpi()) {
            throw new PdfRenderException(PdfRenderException.ERR_INVALID_ARGUMENT,
                    "dpi must be between 1 and " + properties.maxDpi());
        }

        return dpi;
    }
}
