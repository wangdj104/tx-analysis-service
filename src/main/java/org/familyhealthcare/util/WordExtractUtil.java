package org.familyhealthcare.util;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Worddocumentationparseutility class
 * support.doc and .docxformat
 */
public class WordExtractUtil {

    private static final Logger log = LoggerFactory.getLogger(WordExtractUtil.class);

    /**
     * from Wordfileextracttextcontent
     *
     * @param file Wordfile
     * @return textcontent
     */
    public static String extractText(File file) {
        String fileName = file.getName().toLowerCase();
        try (FileInputStream fis = new FileInputStream(file)) {
            if (fileName.endsWith(".docx")) {
                return extractFromDocx(fis, fileName);
            } else if (fileName.endsWith(".doc")) {
                return extractFromDoc(fis, fileName);
            } else {
                throw new IllegalArgumentException("Unsupported file format: " + fileName);
            }
        } catch (IOException e) {
            log.error("Failed to parse Word document: {}", fileName, e);
            throw new RuntimeException("Failed to parse Word document: " + e.getMessage(), e);
        }
    }

    /**
     * from Wordinputflowextracttextcontent
     *
     * @param inputStream inputflow
     * @param fileName    filename
     * @return textcontent
     */
    public static String extractText(InputStream inputStream, String fileName) {
        try {
            if (fileName.toLowerCase().endsWith(".docx")) {
                return extractFromDocx(inputStream, fileName);
            } else if (fileName.toLowerCase().endsWith(".doc")) {
                return extractFromDoc(inputStream, fileName);
            } else {
                throw new IllegalArgumentException("Unsupported file format: " + fileName);
            }
        } catch (IOException e) {
            log.error("Failed to parse Word document: {}", fileName, e);
            throw new RuntimeException("Failed to parse Word document: " + e.getMessage(), e);
        }
    }

    /**
     * parse.docxformat
     */
    private static String extractFromDocx(InputStream inputStream, String fileName) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder sb = new StringBuilder();

            // extractsectionfall
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            log.info("Worddocumentation({})include {} sectionfall, {} tablegrid", fileName, paragraphs.size(), document.getTables().size());

            for (XWPFParagraph paragraph : paragraphs) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(text).append("\n");
                }
            }

            // extracttablegridcontent
            List<XWPFTable> tables = document.getTables();
            for (int i = 0; i < tables.size(); i++) {
                XWPFTable table = tables.get(i);
                sb.append("\n[tablegrid ").append(i + 1).append("]\n");

                for (XWPFTableRow row : table.getRows()) {
                    List<String> cellTexts = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cellTexts.add(cell.getText().trim());
                    }
                    sb.append(String.join(" | ", cellTexts)).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        }
    }

    /**
     * parse.docformat
     */
    private static String extractFromDoc(InputStream inputStream, String fileName) throws IOException {
        try (HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            String text = extractor.getText();
            log.info("Worddocumentation({})extractsuccessful, textlonglevel: {}", fileName, text.length());
            return text;
        }
    }

    /**
     * from Worddocumentationextracttablegriddata (structured)
     *
     * @param file Wordfile
     * @return tablegridlist, each tablegridfor rowlist, each rowfor singleelementgridlist
     */
    public static List<List<List<String>>> extractTables(File file) {
        String fileName = file.getName().toLowerCase();
        try (FileInputStream fis = new FileInputStream(file)) {
            if (fileName.endsWith(".docx")) {
                return extractTablesFromDocx(fis);
            } else if (fileName.endsWith(".doc")) {
                log.warn(".docformatnot supportstructuredtablegridextract, Backtextcontent");
                List<List<List<String>>> result = new ArrayList<>();
                List<List<String>> table = new ArrayList<>();
                List<String> row = new ArrayList<>();
                row.add(extractText(file));
                table.add(row);
                result.add(table);
                return result;
            } else {
                throw new IllegalArgumentException("Unsupported file format: " + fileName);
            }
        } catch (IOException e) {
            log.error("Failed to extract tables from Word document: {}", fileName, e);
            throw new RuntimeException("Failed to extract tables from Word document: " + e.getMessage(), e);
        }
    }

    /**
     * from .docxextracttablegrid
     */
    private static List<List<List<String>>> extractTablesFromDocx(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            List<List<List<String>>> allTables = new ArrayList<>();

            for (XWPFTable table : document.getTables()) {
                List<List<String>> tableData = new ArrayList<>();
                for (XWPFTableRow row : table.getRows()) {
                    List<String> rowData = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        rowData.add(cell.getText().trim());
                    }
                    tableData.add(rowData);
                }
                allTables.add(tableData);
            }

            log.info("from Worddocumentationextract {} tablegrid", allTables.size());
            return allTables;
        }
    }
}
