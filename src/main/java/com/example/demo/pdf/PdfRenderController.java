package com.example.demo.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * HTTP API for converting PDF pages to PNG.
 * <p>
 * Purely a thin adapter: it reads the uploaded file, delegates to {@link PdfRenderService} and
 * shapes the response. Page numbers in this API are <b>1-based</b> (human-friendly); the service
 * and the native code use 0-based indices, so the conversion happens here and nowhere else.
 * <p>
 * Errors are returned as RFC 9457 problem details ({@code application/problem+json}) with the
 * numeric {@code code} included, mapped to
 * 400 (bad input), 503 (all renderers busy, with {@code Retry-After}) or 500 (renderer failure).
 */
@RestController
@RequestMapping("/api/pdf")
public class PdfRenderController {

    static final String PAGE_COUNT_HEADER = "X-Pdf-Page-Count";

    private final PdfRenderService service;

    public PdfRenderController(PdfRenderService service) {
        this.service = service;
    }

    /**
     * Renders one page. The total page count is returned in the {@value #PAGE_COUNT_HEADER}
     * header so a client can paginate without a second request.
     * <p>
     * Example: {@code curl -F file=@doc.pdf 'localhost:8080/api/pdf/png?page=1&dpi=150' -o page.png}
     */
    @PostMapping(value = "/png", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> renderPage(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "dpi", defaultValue = "0") int dpi) throws IOException {

        if (page < 1) {
            throw new PdfRenderException(PdfRenderException.ERR_PAGE_OUT_OF_RANGE, "page must be >= 1");
        }

        byte[] pdf = file.getBytes();
        int pageCount = service.pageCount(pdf);
        byte[] png = service.renderPage(pdf, page - 1, dpi);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(PAGE_COUNT_HEADER, Integer.toString(pageCount))
                .body(png);
    }

    /**
     * Renders every page and returns them as a zip of {@code page-001.png}, {@code page-002.png}, ...
     */
    @PostMapping(value = "/png/all", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> renderAllPages(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "dpi", defaultValue = "0") int dpi) throws IOException {

        List<byte[]> pages = service.renderAllPages(file.getBytes(), dpi);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < pages.size(); i++) {
                zip.putNextEntry(new ZipEntry(String.format("page-%03d.png", i + 1)));
                zip.write(pages.get(i));
                zip.closeEntry();
            }
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(PAGE_COUNT_HEADER, Integer.toString(pages.size()))
                .body(out.toByteArray());
    }

    /**
     * Returns {@code {"pageCount": n}} without rendering anything.
     */
    @PostMapping(value = "/page-count", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Integer> pageCount(@RequestPart("file") MultipartFile file) throws IOException {
        return Map.of("pageCount", service.pageCount(file.getBytes()));
    }

    /**
     * Maps {@link PdfRenderException} codes to HTTP statuses; see the class comment.
     */
    @ExceptionHandler(PdfRenderException.class)
    ResponseEntity<ProblemDetail> handleRenderFailure(PdfRenderException ex) {
        HttpStatus status = ex.isClientError() ? HttpStatus.BAD_REQUEST
                : ex.isBusy() ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(ex.isBusy() ? "PDF renderers busy" : "PDF rendering failed");
        problem.setProperty("code", ex.getCode());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (ex.isBusy()) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }

        return response.body(problem);
    }
}
