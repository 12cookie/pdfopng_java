package com.example.demo.pdf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds tiny, valid PDFs without a PDF library so tests do not need extra dependencies.
 */
final class TestPdfs {

    private TestPdfs() {}

    /**
     * A {@code pages}-page document where every page is 200x100 points and has a
     * red square filling x 10..90, y 10..90; the rest of the page is white.
     */
    static byte[] redSquare(int pages) {
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            kids.append(3 + i * 2).append(" 0 R ");
        }

        objects.add("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pages + " >>");

        String content = "1 0 0 rg 10 10 80 80 re f";
        for (int i = 0; i < pages; i++) {
            int contentObj = 4 + i * 2;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents " + contentObj + " 0 R >>");
            objects.add("<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream");
        }

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }

        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append('\n');
        pdf.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n");
        pdf.append("startxref\n").append(xref).append("\n%%EOF\n");

        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
