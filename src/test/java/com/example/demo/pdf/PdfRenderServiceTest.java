package com.example.demo.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfRenderServiceTest {

    private static PdfRenderService service;

    @BeforeAll
    static void setUp() throws IOException {
        service = new PdfRenderService(new PdfRenderProperties(null, null, 3, Duration.ofSeconds(10), 150, 600));
    }

    @Test
    void poolHasRequestedConcurrency() {
        assertThat(service.concurrency()).isEqualTo(3);
        assertThat(service.available()).isEqualTo(3);
    }

    @Test
    void rendersConcurrentlyFromManyThreadsAndReturnsAllRenderers() throws Exception {
        int tasks = 40;
        byte[] pdf = TestPdfs.redSquare(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<BufferedImage>> results = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int page = i % 2;
                results.add(executor.submit(() -> decodePng(service.renderPage(pdf, page, 72))));
            }

            for (Future<BufferedImage> f : results) {
                BufferedImage image = f.get(30, TimeUnit.SECONDS);
                assertThat(image.getWidth()).isEqualTo(200);
                assertThat(image.getRGB(50, 50) & 0xFFFFFF).isEqualTo(0xFF0000);
            }
        }

        assertThat(service.available()).isEqualTo(3);
    }

    @Test
    void rendererIsReturnedToPoolAfterFailure() {
        assertThatThrownBy(() -> service.renderPage(TestPdfs.redSquare(1), 5, 72))
                .isInstanceOf(PdfRenderException.class);
        assertThat(service.available()).isEqualTo(3);
    }

    @Test
    void countsPages() {
        assertThat(service.pageCount(TestPdfs.redSquare(1))).isEqualTo(1);
        assertThat(service.pageCount(TestPdfs.redSquare(3))).isEqualTo(3);
    }

    @Test
    void rendersPageToPngAtRequestedDpi() throws IOException {
        byte[] png = service.renderPage(TestPdfs.redSquare(1), 0, 72);

        BufferedImage image = decodePng(png);
        assertThat(image.getWidth()).isEqualTo(200);
        assertThat(image.getHeight()).isEqualTo(100);

        // Inside the red square (PDF y-axis points up, so square is at the bottom-left of the image).
        assertThat(image.getRGB(50, 50) & 0xFFFFFF).isEqualTo(0xFF0000);
        // Outside the square: white background.
        assertThat(image.getRGB(150, 50) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test
    void dpiScalesOutput() throws IOException {
        BufferedImage image = decodePng(service.renderPage(TestPdfs.redSquare(1), 0, 144));
        assertThat(image.getWidth()).isEqualTo(400);
        assertThat(image.getHeight()).isEqualTo(200);
    }

    @Test
    void zeroDpiUsesConfiguredDefault() throws IOException {
        BufferedImage image = decodePng(service.renderPage(TestPdfs.redSquare(1), 0, 0));
        // 200pt * 150/72
        assertThat(image.getWidth()).isBetween(416, 417);
    }

    @Test
    void rendersAllPages() throws IOException {
        List<byte[]> pages = service.renderAllPages(TestPdfs.redSquare(3), 72);
        assertThat(pages).hasSize(3);
        for (byte[] png : pages) {
            assertThat(decodePng(png).getWidth()).isEqualTo(200);
        }
    }

    @Test
    void rejectsPageOutOfRange() {
        assertThatThrownBy(() -> service.renderPage(TestPdfs.redSquare(1), 5, 72))
                .isInstanceOf(PdfRenderException.class)
                .hasMessageContaining("out of range")
                .extracting("code").isEqualTo(PdfRenderException.ERR_PAGE_OUT_OF_RANGE);
    }

    @Test
    void rejectsInvalidPdf() {
        assertThatThrownBy(() -> service.pageCount("not a pdf".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(PdfRenderException.class)
                .satisfies(e -> assertThat(((PdfRenderException) e).isClientError()).isTrue());
    }

    @Test
    void rejectsDpiAboveMaximum() {
        assertThatThrownBy(() -> service.renderPage(TestPdfs.redSquare(1), 0, 10_000))
                .isInstanceOf(PdfRenderException.class)
                .hasMessageContaining("dpi");
    }

    private static BufferedImage decodePng(byte[] png) throws IOException {
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        return image;
    }
}
