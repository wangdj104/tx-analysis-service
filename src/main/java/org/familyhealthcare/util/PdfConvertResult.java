package org.familyhealthcare.util;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF convertimageresult (containpage countelementinformation)
 */
public class PdfConvertResult {

    private final List<String> base64Images;
    private final int totalPages;
    private final int processedPages;
    private final boolean truncated;

    public PdfConvertResult(List<String> base64Images, int totalPages, int processedPages) {
        this.base64Images = base64Images != null ? base64Images : new ArrayList<>();
        this.totalPages = totalPages;
        this.processedPages = processedPages;
        this.truncated = totalPages > processedPages;
    }

    public List<String> getBase64Images() {
        return base64Images;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public int getProcessedPages() {
        return processedPages;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public String buildWarningMessage() {
        if (!truncated) {
            return null;
        }
        return String.format("PDF total %d page, systemmost multipleprocessbefore  %d page, exceedpartnot reference and recognition. recommendationsplitfor multiple PDF pointbatchUpload. ",
                totalPages, processedPages);
    }
}
