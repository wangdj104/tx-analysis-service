package org.familyhealthcare.util;

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.familyhealthcare.service.careplan.CareExecutionReportBudget;
import org.familyhealthcare.service.careplan.CareExecutionReportException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.*;

/** Shared PDF mechanics; only the care-report branch accepts the strict local template. */
public final class ReportPdfRenderer {
    private static final Set<String> ELEMENTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "html", "head", "meta", "title", "style", "body", "h1", "h2", "h3", "p", "table", "thead", "tbody", "tr", "th", "td", "a")));

    public byte[] renderLegacy(String html, String fontPath) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            if (!PdfFontSupport.register(builder, candidates(fontPath, false), html) && PdfFontSupport.containsCjk(html)) {
                boolean zh = LocaleContextHolder.getLocale().getLanguage().equals("zh");
                throw new IllegalStateException(zh
                        ? "中文 PDF 导出需要可读取的 TrueType 中文字体。请安装 fonts-wqy-microhei 或配置 REPORT_PDF_FONT_PATH。"
                        : "Chinese PDF export requires a readable TrueType CJK font. Install fonts-wqy-microhei or configure REPORT_PDF_FONT_PATH.");
            }
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        }
    }

    public byte[] renderCareReport(String html, List<String> visibleText, String fontPath, CareExecutionReportBudget budget) {
        Objects.requireNonNull(budget, "budget");
        budget.checkTime();
        try (PDDocument pdf = new PDDocument(MemoryUsageSetting.setupMainMemoryOnly());
             ByteArrayOutputStream bytes = new ByteArrayOutputStream(); OutputStream bounded = budget.output(bytes)) {
            Document document = localDocument(html, budget);
            List<String> actualText = new ArrayList<>(Objects.requireNonNull(visibleText, "visibleText"));
            // Independently include actual body text, so an omitted character cannot evade the font gate.
            collectBodyText(document.getElementsByTagName("body").item(0), actualText, budget);
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            if (!PdfFontSupport.registerAllText(builder, candidates(fontPath, true), actualText, budget)) throw unavailable();
            Element style = document.createElement("style");
            style.setTextContent("body,body *{font-family:'CareReport' !important}body{font-size:12px}table{-fs-table-paginate:paginate;table-layout:fixed}thead{display:table-header-group}thead th:first-child{width:30%}");
            document.getElementsByTagName("head").item(0).appendChild(style);
            // No URI scheme (including data, file and relative URIs) is allowed in this branch.
            builder.useExternalResourceAccessControl((uri, type) -> { throw unavailable(); }, ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.useExternalResourceAccessControl((uri, type) -> { throw unavailable(); }, ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            builder.useUriResolver((base, uri) -> { throw unavailable(); });
            // OpenHTMLToPDF does not close its PDF document when a final save fails.
            // Own it independently so limits, deadlines and construction failures cannot leak it.
            builder.usePDDocument(pdf);
            builder.withW3cDocument(document, null);
            builder.toStream(bounded);
            budget.checkTime();
            try (PdfBoxRenderer renderer = builder.buildPdfRenderer()) {
                renderer.layout();
                budget.checkTime();
                renderer.createPDF();
                budget.checkTime();
            }
            return bytes.toByteArray();
        } catch (CareExecutionReportException failure) {
            throw failure;
        } catch (Exception failure) {
            // Library wrappers must not turn a resource limit/deadline into a rendering error.
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof CareExecutionReportException) throw (CareExecutionReportException) cause;
            }
            throw unavailable(); // Do not expose original text, resource locations or parser diagnostics.
        }
    }

    private static Document localDocument(String html, CareExecutionReportBudget budget) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder parser = factory.newDocumentBuilder();
        parser.setEntityResolver((publicId, systemId) -> { throw new SAXException("External resources are disabled."); });
        parser.setErrorHandler(new DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        });
        Document document = parser.parse(new InputSource(new StringReader(html)));
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            budget.checkTime();
            Element element = (Element) elements.item(i);
            if (!ELEMENTS.contains(element.getTagName())) throw unavailable();
            if (element.getTagName().equals("style")) requireLocalCss(element.getTextContent());
            NamedNodeMap attributes = element.getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                Node attribute = attributes.item(j);
                String name = attribute.getNodeName();
                if (name.equals("style")) requireLocalCss(attribute.getNodeValue());
                else if (name.equals("href") && element.getTagName().equals("a")) {
                    // The visible protected location remains text. PDF has no external or actionable links.
                    element.removeAttribute("href"); j--;
                } else if (!(name.equals("xmlns") || name.equals("lang") || name.equals("charset") || name.equals("scope"))) throw unavailable();
            }
        }
        if (document.getElementsByTagName("head").getLength() != 1 || document.getElementsByTagName("body").getLength() != 1) throw unavailable();
        return document;
    }

    private static void requireLocalCss(String css) {
        String lower = css.toLowerCase(Locale.ROOT);
        // The application's fixed CSS needs no escapes, comments, generated content or resource functions.
        if (lower.contains("\\") || lower.contains("/*") || lower.contains("url") || lower.contains("@import") || lower.contains("content:")) throw unavailable();
    }

    private static void collectBodyText(Node node, List<String> text, CareExecutionReportBudget budget) {
        budget.checkTime();
        if (node == null) throw unavailable();
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) text.add(node.getNodeValue());
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) collectBodyText(child, text, budget);
    }

    private static List<File> candidates(String configured, boolean strict) {
        List<File> files = new ArrayList<>();
        if (configured != null && !configured.trim().isEmpty()) {
            files.add(new File(configured.trim()));
            if (strict) return files; // A configured strict font is authoritative and cannot silently fall back.
        }
        for (String path : new String[]{"/usr/share/fonts/truetype/wqy/wqy-microhei.ttc", "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                "C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simsun.ttc", "C:/Windows/Fonts/simhei.ttf",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"}) files.add(new File(path));
        return files;
    }
    private static CareExecutionReportException unavailable() {
        return new CareExecutionReportException(CareExecutionReportException.Code.REPORT_RENDER_UNAVAILABLE);
    }
}
