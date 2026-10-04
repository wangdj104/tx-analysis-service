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

    @Test void legacyChartDataUrlStillEmbedsItsActualPixels() throws Exception {
        java.awt.image.BufferedImage chart = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = chart.createGraphics();
        graphics.setColor(java.awt.Color.BLUE); graphics.fillRect(0, 0, 8, 8); graphics.dispose();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(chart, "png", png);
        String url = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png.toByteArray());
        HealthReportServiceImpl service = new HealthReportServiceImpl();
        ReflectionTestUtils.setField(service, "pdfFontPath", font("wqy-report-subset.ttf").getAbsolutePath());
        byte[] pdf = ReflectionTestUtils.invokeMethod(service, "renderPdf", "<html><body><p>Synthetic chart</p><img src='" + url + "'/></body></html>");
        try (PDDocument document = PDDocument.load(pdf)) {
            int images = 0;
            for (org.apache.pdfbox.cos.COSName name : document.getPage(0).getResources().getXObjectNames()) {
                org.apache.pdfbox.pdmodel.graphics.PDXObject object = document.getPage(0).getResources().getXObject(name);
                if (object instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                    images++;
                    java.awt.image.BufferedImage image = ((org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) object).getImage();
                    assertEquals(java.awt.Color.BLUE.getRGB(), image.getRGB(0, 0));
                }
            }
            assertEquals(1, images, "Legacy saved chart data URLs must not inherit strict resource denial");
        }
    }

    @Test void strictAllGlyphGateSkipsCffAndMixedCollectionBeforeCompatibleFallback() throws Exception {
        com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
        java.util.List<String> visible = java.util.Collections.singletonList("Care report 患者测试照护执行");
        org.familyhealthcare.service.careplan.CareExecutionReportBudget budget = org.familyhealthcare.service.careplan.CareExecutionReportBudget.start(java.time.Duration.ofSeconds(30));
        assertFalse(org.familyhealthcare.util.PdfFontSupport.registerAllText(builder, java.util.Collections.singletonList(font("noto-cff-subset.ttc")), visible, budget));
        assertTrue(org.familyhealthcare.util.PdfFontSupport.registerAllText(builder, java.util.Arrays.asList(font("noto-cff-subset.ttc"), font("wqy-mixed-coverage.ttc"), font("wqy-care-report-subset.ttf")), visible, budget));
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            builder.withHtmlContent("<html><body style=\"font-family:'CareReport'\">Care report 患者测试照护执行</body></html>", null);
            builder.toStream(output); builder.run();
            try (PDDocument document = PDDocument.load(output.toByteArray())) {
                assertTrue(new PDFTextStripper().getText(document).contains("Care report 患者测试照护执行"));
            }
        }
    }

    private java.io.File font(String name) throws Exception {
        return new java.io.File(getClass().getResource("/fonts/" + name).toURI());
    }
}
