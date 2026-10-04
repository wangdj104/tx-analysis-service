package org.familyhealthcare.service;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.service.careplan.CareExecutionReport.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic reports only. Pixel artifacts remain below target for independent review. */
class CareExecutionReportPdfTest {
    @TempDir Path temporary;

    @Test void bothLanguagesRenderAndExtractAllText() throws Exception {
        for (String language : Arrays.asList("en", "zh-CN")) {
            CareExecutionReport report = CareExecutionReportTestData.exampleReport(language);
            CareExecutionReportBudget budget = budget();
            CareExecutionReportHtmlRenderer.Html html = new CareExecutionReportHtmlRenderer().render(report, budget);
            byte[] pdf = render(html.getMarkup(), html.getVisibleText(), font("wqy-care-report-subset.ttf"), budget);
            assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
            Path dir = Paths.get("target/care-execution-report-qa", language);
            Files.createDirectories(dir); Files.write(dir.resolve("coverage-report.pdf"), pdf);
            try (PDDocument document = PDDocument.load(pdf)) {
                String text = compact(new PDFTextStripper().getText(document));
                for (String visible : html.getVisibleText()) assertTrue(text.contains(compact(visible)), "Missing visible text: " + visible);
                assertTrue(text.contains("原文：按既有计划记录，不自动翻译"));
                for (org.apache.pdfbox.pdmodel.PDPage page : document.getPages()) assertTrue(page.getAnnotations().isEmpty(), "Protected references must stay plain visible text in PDF");
                assertTrue(text.contains(language.equals("en") ? "Careexecutionreport" : "照护执行报告"));
            }
        }
    }

    @Test void missingNonCjkGlyphFailsWithoutSilentLoss() throws Exception {
        for (String original : Arrays.asList("Original 😀", "Original e\u0301", "Original \uD840\uDC00", "Original \uD800", "Original A\uFE0F", "Original \u200D")) {
            CareExecutionReportException error = assertThrows(CareExecutionReportException.class,
                    () -> render(simple(original), Collections.singletonList(original), font("wqy-care-report-subset.ttf"), budget()));
            assertEquals("REPORT_RENDER_UNAVAILABLE", error.getErrorCode());
            assertTrue(error.getMessage().contains("HTML"));
            assertFalse(error.getMessage().contains(original));
            assertNull(error.getCause());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Arabic original: سلام", "Hebrew original: שלום", "Arabic original: سلام Hebrew original: שלום",
            "Unverified Lao original: ກ", "Common-script RTL original: ـ"
    })
    void coveredBidiOrUnverifiedShapingOriginalFailsBeforePdfSuccess(String original) throws Exception {
        CareExecutionReport base = CareExecutionReportTestData.exampleReport("en");
        CurrentAction action = new CurrentAction(10,20,30,7,1,"Synthetic plan","Synthetic instructions",original,
                "OPEN",null,null,false,false,true,null,null,null,null,null,Collections.emptyList());
        CareExecutionReport report = new CareExecutionReport(new Patient(1,"Synthetic patient"),base.getScope(),
                base.getMetadata(),new CurrentSummary(1,0,0,0,0,0),Collections.singletonList(action),
                Collections.emptyList(),new ActivitySummary(0,0,Collections.emptyMap()),Collections.emptyList(),
                "NOT_AUTHORIZED",Collections.emptyList());
        CareExecutionReportBudget budget = budget();
        CareExecutionReportHtmlRenderer.Html html = new CareExecutionReportHtmlRenderer().render(report, budget);
        assertTrue(html.getVisibleText().contains(original), "Original text must reach the capability gate unchanged");
        String path = font("dejavu-bidi-subset.ttf");
        // Every visible character has a real cmap entry and is encodable by PDFBox. A rejection
        // must therefore come from rendering capability, not a missing font or missing glyph.
        try (org.apache.fontbox.ttf.TrueTypeFont font = new org.apache.fontbox.ttf.TTFParser().parse(new File(path));
             PDDocument document = new PDDocument()) {
            org.apache.pdfbox.pdmodel.font.PDType0Font embedded = org.apache.pdfbox.pdmodel.font.PDType0Font.load(document, new File(path));
            for (String text : html.getVisibleText()) {
                for (int point : text.codePoints().toArray()) assertNotEquals(0, font.getUnicodeCmapLookup().getGlyphId(point));
                assertDoesNotThrow(() -> embedded.getStringWidth(text));
            }
        }
        CareExecutionReportException failure = assertThrows(CareExecutionReportException.class,
                () -> render(html.getMarkup(), html.getVisibleText(), path, budget));
        assertEquals("REPORT_RENDER_UNAVAILABLE", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("HTML"));
        assertFalse(failure.getMessage().contains(original));
        assertNull(failure.getCause());
        assertEquals(original, report.getCurrentActions().get(0).getInstruction());
    }

    @Test void missingAndCffFontsFailBeforeReturningAnyBytes() throws Exception {
        for (String path : Arrays.asList(temporary.resolve("missing.ttf").toString(), font("noto-cff-subset.ttc"), font("wqy-report-subset.ttf"))) {
            CareExecutionReportException error = assertThrows(CareExecutionReportException.class,
                    () -> render(simple("患者测试照护执行"), Collections.singletonList("患者测试照护执行"), path, budget()));
            assertEquals("REPORT_RENDER_UNAVAILABLE", error.getErrorCode());
        }
    }

    @Test void networkAndFileResourcesAreDenied() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); byte[] body = "secret".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        server.start();
        Path sentinel = temporary.resolve("private-resource.txt");
        Files.write(sentinel, "PRIVATE_SYNTHETIC_SENTINEL".getBytes(StandardCharsets.UTF_8));
        String http = "http://127.0.0.1:" + server.getAddress().getPort() + "/resource";
        try {
            for (String uri : Arrays.asList(http, sentinel.toUri().toString(), "jar:" + sentinel.toUri() + "!/data", "data:image/png;base64,AAAA", "relative.png")) {
                for (String fragment : Arrays.asList("<img src='" + uri + "'/>", "<link rel='stylesheet' href='" + uri + "'/>", "<style>@import url('" + uri + "');</style>", "<p style=\"background-image:url('" + uri + "')\">Synthetic</p>")) {
                    assertUnavailable("<html><head></head><body>" + fragment + "</body></html>", Collections.singletonList("Synthetic"));
                }
            }
            assertUnavailable("<!DOCTYPE html [<!ENTITY private SYSTEM '" + sentinel.toUri() + "'>]><html><body>&private;</body></html>", Collections.singletonList("Synthetic"));
            assertUnavailable("<!DOCTYPE html SYSTEM '" + http + "'><html><body>Synthetic</body></html>", Collections.singletonList("Synthetic"));
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test void ordinaryWhitespaceDoesNotRequireInvisibleGlyphs() throws Exception {
        byte[] pdf = render(simple("患者测试\nEnglish\ttext\r\nLast line"), Collections.singletonList("患者测试\nEnglish\ttext\r\nLast line"), font("wqy-care-report-subset.ttf"), budget());
        try (PDDocument document = PDDocument.load(pdf)) { assertTrue(new PDFTextStripper().getText(document).contains("Last line")); }
    }

    @Test void multiPageRowsStayReadable() throws Exception {
        for (String language : Arrays.asList("en", "zh-CN")) {
            CareExecutionReportBudget budget = budget();
            CareExecutionReportHtmlRenderer.Html html = new CareExecutionReportHtmlRenderer().render(longReport(language, true), budget);
            byte[] pdf = render(html.getMarkup(), html.getVisibleText(), font("wqy-care-report-subset.ttf"), budget);
            Path dir = Paths.get("target/care-execution-report-qa", language); Files.createDirectories(dir);
            Files.write(dir.resolve("long-report.pdf"), pdf);
            try (PDDocument document = PDDocument.load(pdf)) {
                assertTrue(document.getNumberOfPages() >= 3);
                PDFRenderer renderer = new PDFRenderer(document);
                PDFTextStripper stripper = new PDFTextStripper();
                String extracted = stripper.getText(document);
                if (language.equals("en")) assertTrue(extracted.contains("Published revision ID"), "The label column must not collapse into individual letters");
                String all = compact(extracted);
                for (String visible : html.getVisibleText()) {
                    // Repeated table headers legitimately interrupt the extracted stream at page breaks.
                    for (String line : visible.split("\\r?\\n")) assertTrue(all.contains(compact(line)), "Missing visible text: " + line);
                }
                for (int page = 0; page < document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page + 1); stripper.setEndPage(page + 1);
                    assertFalse(stripper.getText(document).trim().isEmpty(), "Blank overflow page " + (page + 1));
                    BufferedImage image = renderer.renderImageWithDPI(page, 110);
                    assertTrue(image.getWidth() > 800 && image.getHeight() > 1000);
                    ImageIO.write(image, "png", dir.resolve(String.format(Locale.ROOT, "page-%02d.png", page + 1)).toFile());
                }
                assertTrue(all.contains("END-OF-LONG-ORIGINAL"));
            }
        }
    }

    @Test void longUnbrokenOriginalTextDoesNotOverflowThePage() throws Exception {
        String original = String.join("", Collections.nCopies(150, "SyntheticUnbrokenText"));
        CareExecutionReportBudget budget = budget();
        CareExecutionReportHtmlRenderer.Html html = new CareExecutionReportHtmlRenderer().render(reportWithInstruction("en", original), budget);
        byte[] pdf = render(html.getMarkup(), html.getVisibleText(), font("wqy-care-report-subset.ttf"), budget);
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper positions = new PDFTextStripper() {
                @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition position) {
                    assertTrue(position.getXDirAdj() >= 35 && position.getXDirAdj() + position.getWidthDirAdj() <= 565,
                            "Original text exceeds readable A4 horizontal bounds: " + position.getUnicode());
                    super.processTextPosition(position);
                }
            };
            assertTrue(compact(positions.getText(document)).contains(original));
        }
    }

    @Test void finalBytesUseTheSharedBudgetWithoutChargingMarkup() throws Exception {
        CareExecutionReportBudget budget = budget();
        CareExecutionReportHtmlRenderer.Html html = new CareExecutionReportHtmlRenderer().render(longReport("en", false), budget);
        byte[] pdf = render(html.getMarkup(), html.getVisibleText(), font("wqy-care-report-subset.ttf"), budget);
        OutputStream counted = budget.output(new OutputStream() { public void write(int b) { } public void write(byte[] b, int o, int l) { } });
        byte[] block = new byte[8192];
        long remaining = CareExecutionReportContracts.MAX_OUTPUT_BYTES - pdf.length;
        while (remaining > 0) { int amount = (int) Math.min(remaining, block.length); counted.write(block, 0, amount); remaining -= amount; }
        assertEquals("OUTPUT_BYTES", assertThrows(CareExecutionReportException.class, () -> counted.write(1)).getLimitKind());
    }

    @Test void outputLimitAndTimeoutRemainTheirOwnErrorsAndCloseFonts() throws Exception {
        Path copy = temporary.resolve("configured.ttf"); Files.copy(Paths.get(font("wqy-care-report-subset.ttf")), copy);
        for (int attempt = 0; attempt < 3; attempt++) {
            CareExecutionReportBudget limited = budget();
            OutputStream sink = limited.output(new OutputStream() { public void write(int b) { } public void write(byte[] b, int o, int l) { } });
            byte[] block = new byte[8192];
            for (long size = 0; size < CareExecutionReportContracts.MAX_OUTPUT_BYTES; size += block.length) sink.write(block);
            CareExecutionReportException limit = assertThrows(CareExecutionReportException.class, () -> render(simple("患者测试"), Collections.singletonList("患者测试"), copy.toString(), limited));
            assertEquals("REPORT_LIMIT_EXCEEDED", limit.getErrorCode());
            assertEquals("OUTPUT_BYTES", limit.getLimitKind());
        }
        CareExecutionReportException timeout = assertThrows(CareExecutionReportException.class, () -> render(simple("患者测试"), Collections.singletonList("患者测试"), copy.toString(), CareExecutionReportBudget.start(Duration.ZERO)));
        assertEquals("REPORT_TIMEOUT", timeout.getErrorCode());
        Path descriptors = Paths.get("/proc/self/fd");
        if (Files.isDirectory(descriptors)) {
            try (java.util.stream.Stream<Path> paths = Files.list(descriptors)) {
                assertFalse(paths.anyMatch(p -> { try { return Files.readSymbolicLink(p).equals(copy); } catch (IOException e) { return false; } }), "Configured font leaked a descriptor");
            }
        }
        Files.delete(copy);
    }

    @Test void failedPdfWriteClosesItsDocumentInsteadOfLeavingFinalizerCleanup() throws Exception {
        // PDFBox reports an unclosed COSDocument from its finalizer. Flush earlier test objects first.
        System.gc(); System.runFinalization();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.apache.pdfbox.cos.COSDocument");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> log = new ch.qos.logback.core.read.ListAppender<>();
        log.start(); logger.addAppender(log);
        try {
            outputLimitAndTimeoutRemainTheirOwnErrorsAndCloseFonts();
            System.gc(); System.runFinalization();
            assertFalse(log.list.stream().anyMatch(e -> e.getFormattedMessage().contains("You did not close a PDF Document")),
                    "An output-limit failure left the generated PDF document open");
        } finally { logger.detachAppender(log); log.stop(); }
    }

    private static CareExecutionReport longReport(String language, boolean longText) {
        StringBuilder original = new StringBuilder("Synthetic original / 合成原文：患者测试。 ");
        if (longText) for (int i = 1; i <= 95; i++) original.append("Line ").append(i).append(" synthetic note, preserved without translation. 合成备注，保留原文。\n");
        if (longText) original.append(String.join("", Collections.nCopies(50, "SyntheticUnbrokenText"))).append("\n");
        original.append("END-OF-LONG-ORIGINAL");
        return reportWithInstruction(language, original.toString());
    }
    private static CareExecutionReport reportWithInstruction(String language, String instruction) {
        CareExecutionReport base = CareExecutionReportTestData.exampleReport(language);
        CurrentAction action = new CurrentAction(10,20,30,7,1,"Synthetic plan / 合成计划","Synthetic published instructions",instruction,"OPEN",null,null,false,false,true,null,null,null,null,null,Collections.emptyList());
        return new CareExecutionReport(new Patient(1,"患者测试 / Synthetic patient"),base.getScope(),base.getMetadata(),new CurrentSummary(1,0,0,0,0,0),Collections.singletonList(action),Collections.emptyList(),new ActivitySummary(0,0,Collections.emptyMap()),Collections.emptyList(),"AVAILABLE",Collections.singletonList(new Question(401,"Synthetic question / 合成问题","OPEN","Synthetic description",null,null,"Synthetic owner",1L,"2026-09-01 08:30:00",null,null)));
    }
    private void assertUnavailable(String html, List<String> visible) throws Exception {
        assertEquals("REPORT_RENDER_UNAVAILABLE", assertThrows(CareExecutionReportException.class, () -> render(html, visible, font("wqy-care-report-subset.ttf"), budget())).getErrorCode());
    }
    private static String simple(String text) { return "<html><head></head><body><p>" + text + "</p></body></html>"; }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
    private static CareExecutionReportBudget budget() { return CareExecutionReportBudget.start(Duration.ofSeconds(30)); }
    private String font(String name) throws Exception { return new File(getClass().getResource("/fonts/" + name).toURI()).getAbsolutePath(); }
    private byte[] render(String html, List<String> visible, String font, CareExecutionReportBudget budget) throws Exception {
        return new org.familyhealthcare.util.ReportPdfRenderer().renderCareReport(html, visible, font, budget);
    }
}
