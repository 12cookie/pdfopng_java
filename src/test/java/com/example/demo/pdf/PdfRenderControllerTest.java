package com.example.demo.pdf;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PdfRenderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static MockMultipartFile pdf(int pages) {
        return new MockMultipartFile("file", "test.pdf", MediaType.APPLICATION_PDF_VALUE, TestPdfs.redSquare(pages));
    }

    @Test
    void rendersRequestedPageAsPng() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/pdf/png").file(pdf(2)).param("page", "2").param("dpi", "72"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(PdfRenderController.PAGE_COUNT_HEADER, "2"))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void rendersAllPagesAsZip() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/pdf/png/all").file(pdf(3)).param("dpi", "72"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string(PdfRenderController.PAGE_COUNT_HEADER, "3"))
                .andReturn();

        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }

        assertThat(names).containsExactly("page-001.png", "page-002.png", "page-003.png");
    }

    @Test
    void reportsPageCount() throws Exception {
        mockMvc.perform(multipart("/api/pdf/page-count").file(pdf(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(4));
    }

    @Test
    void pageOutOfRangeIsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/pdf/png").file(pdf(1)).param("page", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("PDF rendering failed"))
                .andExpect(jsonPath("$.code").value(PdfRenderException.ERR_PAGE_OUT_OF_RANGE));
    }

    @Test
    void invalidPdfIsBadRequest() throws Exception {
        MockMultipartFile garbage = new MockMultipartFile("file", "x.pdf", MediaType.APPLICATION_PDF_VALUE, "nope".getBytes());
        mockMvc.perform(multipart("/api/pdf/png").file(garbage))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PdfRenderException.ERR_LOAD_DOCUMENT));
    }
}
