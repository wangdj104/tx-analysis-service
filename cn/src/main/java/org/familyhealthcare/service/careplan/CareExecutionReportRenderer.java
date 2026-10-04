package org.familyhealthcare.service.careplan;

import org.familyhealthcare.util.ReportPdfRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Dispatches an already-authorized immutable projection; no database or external resource reads. */
@Component
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
public class CareExecutionReportRenderer {
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final CareExecutionReportHtmlRenderer html;
    private final CareExecutionReportCsvRenderer csv;
    private final ReportPdfRenderer pdf=new ReportPdfRenderer();
    private final String fontPath;
    public CareExecutionReportRenderer(CareExecutionReportHtmlRenderer html,CareExecutionReportCsvRenderer csv,
            @Value("${REPORT_PDF_FONT_PATH:}") String fontPath) {
        this.html=Objects.requireNonNull(html);this.csv=Objects.requireNonNull(csv);this.fontPath=fontPath;
    }
    public Export render(CareExecutionReport report,CareExecutionReportContracts.Format format,CareExecutionReportBudget budget) {
        Objects.requireNonNull(report);Objects.requireNonNull(budget).checkTime();
        byte[] bytes;String type,kind,extension;
        if(format==CareExecutionReportContracts.Format.ACTIONS_CSV||format==CareExecutionReportContracts.Format.EVENTS_CSV) {
            bytes=csv.render(report,format,budget); // CSV already charges the final byte stream exactly once.
            type="text/csv;charset=UTF-8";kind=format==CareExecutionReportContracts.Format.ACTIONS_CSV?"actions":"events";extension="csv";
        }else if(format==CareExecutionReportContracts.Format.HTML||format==CareExecutionReportContracts.Format.PDF) {
            CareExecutionReportHtmlRenderer.Html rendered=html.render(report,budget); // Separately bounded intermediate XHTML.
            if(format==CareExecutionReportContracts.Format.PDF) {
                bytes=pdf.renderCareReport(rendered.getMarkup(),rendered.getVisibleText(),fontPath,budget);
                type="application/pdf";kind="pdf";extension="pdf";
            }else {
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(Writer writer=new OutputStreamWriter(budget.output(out),StandardCharsets.UTF_8)){writer.write(rendered.getMarkup());}
                catch(IOException exception){throw new CareExecutionReportException(CareExecutionReportException.Code.REPORT_RENDER_UNAVAILABLE);}
                bytes=out.toByteArray();type="text/html;charset=UTF-8";kind="html";extension="html";
            }
        }else throw CareExecutionReportException.invalid();
        budget.checkTime();
        return new Export(bytes,type,"care-execution-report-"+report.getMetadata().getLanguage()+"-"+STAMP.format(report.getMetadata().getGeneratedAt())+"-"+kind+"."+extension);
    }
    /** Owned, fully buffered bytes. Instances remain inside the request until final authorization. */
    public static final class Export {
        private final byte[] content;
        private final String contentType,fileName;
        public Export(byte[] content,String contentType,String fileName){this.content=Objects.requireNonNull(content);this.contentType=Objects.requireNonNull(contentType);this.fileName=Objects.requireNonNull(fileName);}
        public byte[] getContent(){return content;}
        public String getContentType(){return contentType;}
        public String getFileName(){return fileName;}
    }
}
