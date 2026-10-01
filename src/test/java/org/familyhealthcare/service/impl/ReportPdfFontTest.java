package org.familyhealthcare.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class ReportPdfFontTest {
    @Test void chineseReportExportsReadableTextInsteadOfUnsupportedFontFailure() throws Exception {
        HealthReportServiceImpl service = new HealthReportServiceImpl();
        ReflectionTestUtils.setField(service, "pdfFontPath", new java.io.File(getClass().getResource("/fonts/wqy-report-subset.ttf").toURI()).getAbsolutePath());
        byte[] pdf = ReflectionTestUtils.invokeMethod(service, "renderPdf", "<html><body style=\"font-family:'Microsoft YaHei'\">患者测试张爱华健康报告血压血糖</body></html>");
        try (PDDocument document = PDDocument.load(pdf)) {
            assertTrue(new PDFTextStripper().getText(document).contains("患者测试张爱华健康报告血压血糖"));
        }
    }
    @Test void cffCollectionIsSkippedBeforeRenderingWithCompatibleFallback() throws Exception {
        com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
        String html = "<html><body style=\"font-family:'Microsoft YaHei'\">患者测试</body></html>";
        java.io.File unsupported = font("noto-cff-subset.ttc"), supported = font("wqy-report-subset.ttf");
        assertFalse(org.familyhealthcare.util.PdfFontSupport.register(builder, java.util.Collections.singletonList(unsupported), html));
        assertTrue(org.familyhealthcare.util.PdfFontSupport.register(builder, java.util.Arrays.asList(unsupported, supported), html));
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            builder.withHtmlContent(html, null); builder.toStream(output); builder.run();
            java.nio.file.Path result = java.nio.file.Paths.get("target/pdf-font-regression.pdf");
            java.nio.file.Files.write(result, output.toByteArray());
            try (PDDocument document = PDDocument.load(output.toByteArray())) {
                assertTrue(new PDFTextStripper().getText(document).contains("患者测试"));
                java.awt.image.BufferedImage image = new org.apache.pdfbox.rendering.PDFRenderer(document).renderImage(0);
                assertTrue(image.getWidth() > 100 && image.getHeight() > 100);
                javax.imageio.ImageIO.write(image, "png", new java.io.File("target/pdf-font-regression.png"));
            }
        }
    }

    @Test void mixedCoverageCollectionCannotHideTheNextCompatibleFont() throws Exception {
        com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
        String html = "<html><body style=\"font-family:'Microsoft YaHei'\">患者测试</body></html>";
        assertTrue(org.familyhealthcare.util.PdfFontSupport.register(builder,
                java.util.Arrays.asList(font("wqy-mixed-coverage.ttc"), font("wqy-report-subset.ttf")), html));
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            builder.withHtmlContent(html, null); builder.toStream(output); builder.run();
            try (PDDocument document = PDDocument.load(output.toByteArray())) {
                assertTrue(new PDFTextStripper().getText(document).contains("患者测试"));
            }
        }
    }

    @Test void unavailableOrIncompleteFontDoesNotClaimChineseCoverage() throws Exception {
        com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
        assertFalse(org.familyhealthcare.util.PdfFontSupport.register(builder, java.util.Collections.singletonList(new java.io.File("missing-font.ttf")), "患者测试"));
        assertFalse(org.familyhealthcare.util.PdfFontSupport.register(builder, java.util.Collections.singletonList(font("wqy-report-subset.ttf")), "\uD840\uDC00"));
        assertTrue(org.familyhealthcare.util.PdfFontSupport.containsCjk("\uD840\uDC00"));
        assertFalse(org.familyhealthcare.util.PdfFontSupport.containsCjk("English report"));
    }

    private java.io.File font(String name) throws Exception {
        return new java.io.File(getClass().getResource("/fonts/" + name).toURI());
    }
}
