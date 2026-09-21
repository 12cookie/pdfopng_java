package com.example.demo.pdf;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under the {@code pdf.render.*} prefix in {@code application.properties}. Spring Boot
 * binds them onto this record (kebab-case keys map to camelCase components, e.g.
 * {@code pdf.render.acquire-timeout} → {@code acquireTimeout}); the compact constructor fills in
 * defaults for anything left unset.
 *
 * @param libraryPath    explicit path to {@code libpdf_render}; when unset the classpath bundle or cargo output is used
 * @param pdfiumPath     explicit path to {@code libpdfium}; when unset the bundled copy, the download in
 *                       {@code native/pdf-render/lib}, or well-known system locations are used
 * @param concurrency    number of independent renderer instances (each a separate copy of the native
 *                       libraries). {@code 0} uses every slot the build bundled ({@code pdf.render.slots} in
 *                       pom.xml). Cannot exceed the bundled count.
 * @param acquireTimeout how long a request waits for a free renderer before failing with 503
 * @param defaultDpi     resolution used when a request does not specify one
 * @param maxDpi         upper bound accepted from requests, guarding against huge allocations
 */
@ConfigurationProperties(prefix = "pdf.render")
public record PdfRenderProperties(
        Path libraryPath,
        Path pdfiumPath,
        int concurrency,
        Duration acquireTimeout,
        int defaultDpi,
        int maxDpi) {

    public PdfRenderProperties {
        if (acquireTimeout == null) {
            acquireTimeout = Duration.ofSeconds(30);
        }
        if (defaultDpi <= 0) {
            defaultDpi = 150;
        }
        if (maxDpi <= 0) {
            maxDpi = 600;
        }
    }
}
