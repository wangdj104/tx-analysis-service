package org.familyhealthcare.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

/**
 * PDFconvertimageutility class
 * will PDFfile each onepageconvertchangefor image (Base64format)
 */
public class PdfToImageUtil {

    private static final Logger log = LoggerFactory.getLogger(PdfToImageUtil.class);

    /** renderDPI: 150enoughenoughclear, andcompared with300fast4times, filesmall75% */
    private static final float RENDER_DPI = 150;
    /** maximumoutputwidthlevel (pixels) , exceedthenetc.compared withcompressplace */
    private static final int MAX_IMAGE_WIDTH = 2048;
    /** JPEGcompressqualityamount (0.0-1.0)  */
    private static final float JPEG_QUALITY = 0.85f;
    /** maximumprocesspage countlimit */
    private static final int MAX_PAGES = 30;

    public static int getMaxPages() {
        return MAX_PAGES;
    }

    /**
     * will PDFfileconvertchangefor Base64imagelist (optimizeversion: lowDPI, compress, limitpage count)
     */
    public static PdfConvertResult pdfToBase64ImagesWithMeta(File pdfFile) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            return convertDocumentToImages(document);
        } catch (IOException e) {
            log.error("PDFconvertimagefailed: {}", pdfFile.getName(), e);
            throw new RuntimeException("PDFFailed to parse file: " + e.getMessage(), e);
        }
    }

    public static List<String> pdfToBase64Images(File pdfFile) {
        return pdfToBase64ImagesWithMeta(pdfFile).getBase64Images();
    }

    /**
     * will PDFinputflowconvertchangefor Base64imagelist (optimizeversion)
     */
    public static PdfConvertResult pdfToBase64ImagesWithMeta(InputStream inputStream, String fileName) {
        try (PDDocument document = PDDocument.load(inputStream)) {
            return convertDocumentToImages(document);
        } catch (IOException e) {
            log.error("PDFconvertimagefailed: {}", fileName, e);
            throw new RuntimeException("PDFFailed to parse file: " + e.getMessage(), e);
        }
    }

    public static List<String> pdfToBase64Images(InputStream inputStream, String fileName) {
        return pdfToBase64ImagesWithMeta(inputStream, fileName).getBase64Images();
    }

    /**
     * will PDFdocumentationconvertchangefor imagelist (bringcompress and page countlimit)
     */
    private static PdfConvertResult convertDocumentToImages(PDDocument document) throws IOException {
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        int pageCount = document.getNumberOfPages();
        int processCount = Math.min(pageCount, MAX_PAGES);
        List<String> base64Images = new ArrayList<>();

        log.info("PDFtotal {} page, processbefore  {} page (most multiple{}page) ...", pageCount, processCount, MAX_PAGES);

        for (int i = 0; i < processCount; i++) {
            long startTime = System.currentTimeMillis();

            // 1. renderPDFpagefor image (150 DPI)
            BufferedImage image = pdfRenderer.renderImageWithDPI(i, RENDER_DPI);

            // 2. ifimagepastwidth, etc.compared withcompressplace (decreasefewAItransmit and recognitionTime)
            BufferedImage resized = resizeIfNeeded(image);

            // 3. compressfor JPEG (compared withPNGsmall70-80%)
            byte[] jpegBytes = compressToJpeg(resized);

            String base64 = Base64.getEncoder().encodeToString(jpegBytes);
            base64Images.add("data:image/jpeg;base64," + base64);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("No.  {} pageconvertchangecomplete, originalsize {}x{}, output {} KB, consumetime {}ms",
                    i + 1, image.getWidth(), image.getHeight(), jpegBytes.length / 1024, elapsed);
        }

        if (pageCount > MAX_PAGES) {
            log.warn("PDFpage count {} exceedmaximumlimit {}, onlyprocessbefore  {} page", pageCount, MAX_PAGES, MAX_PAGES);
        }

        return new PdfConvertResult(base64Images, pageCount, processCount);
    }

    /**
     * ifimagewidthlevelexceedlimit, etc.compared withcompressplace
     */
    private static BufferedImage resizeIfNeeded(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        if (width <= MAX_IMAGE_WIDTH) {
            return source;
        }

        double scale = (double) MAX_IMAGE_WIDTH / width;
        int newWidth = MAX_IMAGE_WIDTH;
        int newHeight = (int) (height * scale);

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(source, 0, 0, newWidth, newHeight, null);
        g2d.dispose();

        return scaled;
    }

    /**
     * will imagecompressfor JPEGbytescountgroup
     */
    private static byte[] compressToJpeg(BufferedImage image) throws IOException {
        // convertfor RGB (goremovetransparentchannel, JPEGnot support)
        BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgbImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            // returnexitto standardImageIO
            ImageIO.write(rgbImage, "jpeg", baos);
            return baos.toByteArray();
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(rgbImage, null, null), param);
        } finally {
            writer.dispose();
        }

        return baos.toByteArray();
    }

    /**
     * getPDFpage count
     */
    public static int getPageCount(File pdfFile) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            log.error("getPDFpage countfailed: {}", pdfFile.getName(), e);
            return 0;
        }
    }

    /**
     * getPDFpage count (from inputflow)
     */
    public static int getPageCount(InputStream inputStream) {
        try (PDDocument document = PDDocument.load(inputStream)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            log.error("getPDFpage countfailed", e);
            return 0;
        }
    }
}
