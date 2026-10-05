package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.service.careplan.CareExecutionReport.*;
import org.familyhealthcare.service.careplan.CareExecutionReportContracts.Format;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic fixtures only. Expectations do not use the renderer's escaping or column helpers. */
class CareExecutionReportRenderTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final String NOTE = "原文, \"保留\"\r\n第二行\n全角＝ Unicode−";
    private CareExecutionReportHtmlRenderer.Html html(CareExecutionReport r, CareExecutionReportBudget b) {
        return new CareExecutionReportHtmlRenderer().render(r,b);
    }
    private String markup(CareExecutionReportHtmlRenderer.Html html) { return html.getMarkup(); }
    private List<String> visible(CareExecutionReportHtmlRenderer.Html html) { return html.getVisibleText(); }
    private byte[] csv(CareExecutionReport r, Format f) {
        return new CareExecutionReportCsvRenderer().render(r,f,budget());
    }
    private CareExecutionReportBudget budget() { return CareExecutionReportBudget.start(Duration.ofSeconds(30)); }

    @Test void htmlEscapesEveryDynamicField() throws Exception {
        String attack = "<script>alert(\"x\")</script>&'";
        List<String> originals = new ArrayList<>();
        java.util.function.Function<String,String> text = name -> {String v=name+attack; originals.add(v); return v;};
        EventSummary event = new EventSummary(20,30,"RECEIPT_SUBMITTED",text.apply("actor"),text.apply("role"),text.apply("relation"),text.apply("mode"),text.apply("note"),text.apply("follow-up"),NOW,NOW,Arrays.asList(new Evidence(false,"MEASUREMENT",44L,text.apply("evidence"),"/care-journey?tab=measurements&patientId=7&measurementId=44"),new Evidence(true,"SECRET",99L,"SECRET-TITLE","https://secret.test")));
        CurrentAction a = new CurrentAction(10,11,12,30,2,text.apply("plan"),text.apply("instructions"),text.apply("instruction"),"SUBMITTED",NOW,NOW,false,false,false,event,event,event,event,event,event.getEvidence());
        Question q = new Question(40,text.apply("question"),text.apply("question-status"),text.apply("description"),text.apply("answer"),text.apply("next"),text.apply("question-actor"),30L,text.apply("created"),text.apply("updated"),text.apply("question-event"));
        CareExecutionReport r = report("en",text.apply("patient"),Arrays.asList(a),Arrays.asList(new PeriodEvent(event,10,11,12L,2,text.apply("old-plan"),text.apply("old-instructions"),text.apply("old-action"),"SUBMITTED","ACTIVE",true)),"AVAILABLE",Arrays.asList(q));
        String out = markup(html(r,budget()));
        assertFalse(out.contains("<script>alert"));
        assertTrue(out.contains("&lt;script&gt;"));
        for(String original:originals) assertTrue(out.contains(original.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")), original);
        assertFalse(out.contains("SECRET-TITLE")); assertFalse(out.contains("https://secret.test"));
        assertTrue(out.contains("href=\"/care-journey?tab=measurements&amp;patientId=7&amp;measurementId=44\""));
        assertTrue(out.contains("thead")); assertTrue(out.contains("table-header-group")); assertFalse(out.contains("overflow:hidden"));
    }

    @Test void persistedLifecycleLabelsKeepCodesInHtmlAndEventCsv() throws Exception {
        String[] codes={"ACTIVE","COMPLETED","CANCELLED","UNKNOWN_LIFECYCLE"};
        String[][] names={{"Active","Closed","Cancelled","UNKNOWN_LIFECYCLE"},{"有效","已关闭","已取消","UNKNOWN_LIFECYCLE"}};
        for(int language=0;language<2;language++) for(int i=0;i<codes.length;i++) {
            EventSummary event=new EventSummary(901,9,"COMPLETED".equals(codes[i])?"PLAN_CLOSED":"PLAN_PUBLISHED","Synthetic doctor","DOCTOR",null,null,"原始声明",null,NOW,null,Collections.emptyList());
            PeriodEvent period=new PeriodEvent(event,10,11,null,1,"Synthetic plan","Original instructions",null,null,codes[i],false);
            CareExecutionReport r=report(language==0?"en":"zh-CN","Synthetic patient",Collections.emptyList(),Collections.singletonList(period),"NOT_AUTHORIZED",Collections.emptyList());
            assertTrue(markup(html(r,budget())).contains(names[language][i]+" ("+codes[i]+")"));
            assertTrue(new String(csv(r,Format.EVENTS_CSV),StandardCharsets.UTF_8).contains("\""+codes[i]+"\",\""+names[language][i]+"\""));
            assertEquals(codes[i],period.getPlanLifecycleAtGeneration());
            assertEquals(event.getEventType(),period.getEventType());
        }
    }

    @Test void csvPreservesTwoRowModels() throws Exception {
        byte[] actions = csv(fixture("en"),Format.ACTIONS_CSV), events = csv(fixture("zh-CN"),Format.EVENTS_CSV);
        assertEquals(golden("care-execution-actions-en.csv"),new String(actions,StandardCharsets.UTF_8));
        assertEquals(golden("care-execution-events-zh-CN.csv"),new String(events,StandardCharsets.UTF_8));
        assertTrue(new String(actions,StandardCharsets.UTF_8).startsWith("\uFEFF"));
        Path qa=Paths.get("target/care-execution-report-qa");Files.createDirectories(qa);
        Files.write(qa.resolve("actions-en.csv"),actions);Files.write(qa.resolve("events-zh-CN.csv"),events);
        assertFalse(new String(actions,StandardCharsets.UTF_8).contains("QUESTION-ONLY"));
        assertFalse(new String(events,StandardCharsets.UTF_8).contains("QUESTION-ONLY"));
    }

    @Test void dangerousTextIsLiteral() throws Exception {
        String[][] cases={{"=1+1","'=1+1"},{" +SUM(1)","' +SUM(1)"},{"\u2003-1","'\u2003-1"},{"\u00a0@x","'\u00a0@x"},{"\tplain","'\tplain"},{" \u0001plain","' \u0001plain"},{"\r\nplain","'\r\nplain"},{"\uFEFF=1","'\uFEFF=1"},{"−1","−1"},{"全角＝1","全角＝1"},{"42","42"},{"plain\tinside","plain\tinside"},{"a,\"b\"\r\nc","a,\"b\"\r\nc"}};
        for(String[] c:cases) {
            CurrentAction a=action(c[0],Collections.emptyList(),false);
            String out=new String(csv(report("en","Synthetic patient",Arrays.asList(a),Collections.emptyList(),"NOT_AUTHORIZED",Collections.emptyList()),Format.ACTIONS_CSV),StandardCharsets.UTF_8);
            assertTrue(out.contains(",\""+c[1].replace("\"","\"\"")+"\","),"literal protection for "+Arrays.toString(c));
            assertEquals(c[0],a.getInstruction());
        }
        String safe=new String(csv(fixture("en"),Format.ACTIONS_CSV),StandardCharsets.UTF_8);
        assertTrue(safe.contains(",7,")); // Typed patient ID remains a number, not apostrophe-prefixed text.
        assertThrows(CareExecutionReportException.class,()->csv(fixture("en"),Format.HTML));
    }

    @Test void emptyCsvHasHeaderOnly() throws Exception {
        for(String lang:Arrays.asList("en","zh-CN")) for(Format f:Arrays.asList(Format.ACTIONS_CSV,Format.EVENTS_CSV)) {
            CareExecutionReport r=report(lang,"Synthetic empty",Collections.emptyList(),Collections.emptyList(),"NOT_AUTHORIZED",Collections.emptyList());
            String out=new String(csv(r,f),StandardCharsets.UTF_8);
            assertTrue(out.startsWith("\uFEFF\""+(lang.equals("en")?"Report schema version":"报告结构版本")+"\""));
            assertEquals(1,out.split("\r\n",-1).length-1);
            assertTrue(out.endsWith("\r\n"));assertFalse(out.contains(NOW.toString()));
            Path qa=Paths.get("target/care-execution-report-qa");Files.createDirectories(qa);Files.write(qa.resolve("empty-"+f.name()+"-"+lang+".csv"),out.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void localeNeverTranslatesClinicalNotes() throws Exception {
        LocaleContextHolder.setLocale(Locale.CHINA);
        try {
            CareExecutionReport en=fixture("en"), cn=fixture("zh-CN");
            String e=markup(html(en,budget())), c=markup(html(cn,budget()));
            assertTrue(e.contains("Care execution report"));assertTrue(c.contains("照护执行报告"));
            assertTrue(e.contains("Submitted awaiting doctor review"));assertTrue(c.contains("已提交待医生复核"));
            assertTrue(e.contains("Reviewed by doctor"));assertTrue(c.contains("医生已复核"));
            assertTrue(visible(html(en,budget())).contains(NOTE));assertTrue(visible(html(cn,budget())).contains(NOTE));
            assertEquals(NOTE,en.getPeriodEvents().get(0).getNote()); assertEquals(NOTE,cn.getPeriodEvents().get(0).getNote());
            assertTrue(e.contains("Asia/Shanghai"));assertTrue(e.contains("+08:00"));
            assertTrue(e.contains("Recorded answer"));assertFalse(e.contains("Completion rate"));
            assertTrue(e.contains("CSV text safety"));assertTrue(c.contains("CSV文本安全"));
        } finally {LocaleContextHolder.resetLocaleContext();}
    }

    @Test void visibleTextIncludesEveryRenderedBodyTextNode() throws Exception {
        for(String lang:Arrays.asList("en","zh-CN")) {
            CareExecutionReportHtmlRenderer.Html out=html(fixture(lang),budget());
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            Document doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(markup(out).getBytes(StandardCharsets.UTF_8)));
            List<String> nodes=new ArrayList<>();textNodes(doc.getElementsByTagName("body").item(0),nodes);
            assertEquals(nodes,visible(out));
            assertThrows(UnsupportedOperationException.class,()->visible(out).add("extra"));
        }
    }

    @Test void safeEvidenceLocationsExcludeExternalAndMismatchedTargets() throws Exception {
        for(String bad:Arrays.asList("javascript:alert(1)","https://evil.test/","//evil.test/","file:///tmp/private","/care-journey?tab=measurements&patientId=8&measurementId=44","/care-journey?tab=measurements&patientId=7&measurementId=45","/care-journey?tab=measurements&patientId=7&measurementId=44#x")) {
            CareExecutionReport r=report("en","Synthetic",Arrays.asList(action("Safe original",Arrays.asList(new Evidence(false,"MEASUREMENT",44L,"Synthetic reference",bad)),false)),Collections.emptyList(),"NOT_AUTHORIZED",Collections.emptyList());
            String out=markup(html(r,budget())); assertFalse(out.contains("href="));assertFalse(out.contains(bad));
            assertFalse(new String(csv(r,Format.ACTIONS_CSV),StandardCharsets.UTF_8).contains(bad));
        }
    }

    @Test void questionsAvailabilityAndZeroCurrentCountsRemainExplicit() throws Exception {
        for(String availability:Arrays.asList("NOT_AUTHORIZED","NOT_INCLUDED_IN_PLAN_SCOPE","AVAILABLE")) {
            CareExecutionReport r=report("en","Synthetic empty",Collections.emptyList(),Collections.emptyList(),availability,Collections.emptyList());
            String out=markup(html(r,budget()));
            assertTrue(out.contains(availability.equals("NOT_AUTHORIZED")?"Questions are not authorized":availability.equals("NOT_INCLUDED_IN_PLAN_SCOPE")?"Questions are not included in a single-plan report":"No questions"));
            assertTrue(out.contains("No current active-plan actions"));assertTrue(out.contains("Not applicable"));
        }
    }

    @Test void htmlIntermediateBoundDoesNotSpendFinalOutputBudget() throws Exception {
        CareExecutionReportBudget b=budget();CareExecutionReportHtmlRenderer.Html out=html(fixture("en"),b);
        OutputStream finalBytes=b.output(new OutputStream(){@Override public void write(int ignored){} @Override public void write(byte[] v,int o,int n){}});
        byte[] chunk=new byte[1024*1024];for(int i=0;i<32;i++)finalBytes.write(chunk);
        assertFalse(markup(out).isEmpty());
        assertEquals("OUTPUT_BYTES",assertThrows(CareExecutionReportException.class,()->finalBytes.write(1)).getLimitKind());
    }

    @Test void htmlAndCsvRejectOutputOverflowAndExpiredBudget() throws Exception {
        char[] chars=new char[7*1024*1024];Arrays.fill(chars,'&');String longText=new String(chars);
        CareExecutionReport r=report("en","Synthetic",Arrays.asList(action(longText,Collections.emptyList(),false)),Collections.emptyList(),"NOT_AUTHORIZED",Collections.emptyList());
        assertEquals("OUTPUT_BYTES",assertThrows(CareExecutionReportException.class,()->html(r,budget())).getLimitKind());
        // CSV copies the clinical instruction once. Many rows can exceed final bytes without an intermediate unbounded CSV string.
        List<CurrentAction> rows=new ArrayList<>();for(int i=0;i<6;i++)rows.add(action(longText,Collections.emptyList(),false));
        CareExecutionReport many=report("en","Synthetic",rows,Collections.emptyList(),"NOT_AUTHORIZED",Collections.emptyList());
        assertEquals("OUTPUT_BYTES",assertThrows(CareExecutionReportException.class,()->csv(many,Format.ACTIONS_CSV)).getLimitKind());
        assertEquals("REPORT_TIMEOUT",assertThrows(CareExecutionReportException.class,()->html(fixture("en"),CareExecutionReportBudget.start(Duration.ZERO))).getErrorCode());
    }

    @Test void csvReadsOnlyItsRequiredProjection() throws Exception {
        CareExecutionReport r=fixture("en");
        CareExecutionReport onlyActions=new CareExecutionReport(r.getPatient(),r.getScope(),r.getMetadata(),null,r.getCurrentActions(),null,null,null,"NOT_AUTHORIZED",null);
        CareExecutionReport onlyEvents=new CareExecutionReport(r.getPatient(),r.getScope(),r.getMetadata(),null,null,null,null,r.getPeriodEvents(),"NOT_AUTHORIZED",null);
        assertArrayEquals(csv(r,Format.ACTIONS_CSV),csv(onlyActions,Format.ACTIONS_CSV));
        assertArrayEquals(csv(r,Format.EVENTS_CSV),csv(onlyEvents,Format.EVENTS_CSV));
    }

    private static void textNodes(Node n,List<String> out) {if(n.getNodeType()==Node.TEXT_NODE&&!n.getNodeValue().isEmpty())out.add(n.getNodeValue());for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())textNodes(c,out);}
    private static String golden(String name) throws IOException {try(InputStream in=CareExecutionReportRenderTest.class.getResourceAsStream("/reports/"+name)){assertNotNull(in);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static CurrentAction action(String instruction,List<Evidence> evidence,boolean summaries) {
        EventSummary receipt=summaries?new EventSummary(101,31,"RECEIPT_SUBMITTED","Synthetic recorder","FAMILY","SPOUSE","ASSISTED",NOTE,null,NOW.minusSeconds(7200),NOW.minusSeconds(10800),evidence):null;
        EventSummary returned=summaries?new EventSummary(102,32,"RECEIPT_RETURNED","Synthetic doctor","DOCTOR",null,null," =return",null,NOW.minusSeconds(6000),null,Collections.emptyList()):null;
        EventSummary review=summaries?new EventSummary(103,32,"RECEIPT_CONFIRMED","Synthetic doctor","DOCTOR",null,null," +review",null,NOW.minusSeconds(5000),null,Collections.emptyList()):null;
        EventSummary help=summaries?new EventSummary(104,31,"HELP_REQUESTED","Synthetic recorder","FAMILY","SPOUSE","SELF"," -help",null,NOW.minusSeconds(4000),null,Collections.emptyList()):null;
        EventSummary follow=summaries?new EventSummary(105,33,"FOLLOW_UP_RECORDED","Synthetic nurse","NURSE",null,null,"\t@follow","CONTACTED",NOW.minusSeconds(3000),null,Collections.emptyList()):null;
        return new CurrentAction(10,11,summaries?12:13,31,2,"Synthetic, \"plan\"","原文指示\r\n保留",instruction,summaries?"SUBMITTED":"CONFIRMED",NOW.plusSeconds(86400),summaries?NOW.minusSeconds(7200):null,false,false,true,receipt,returned,review,help,follow,evidence);
    }
    private static CareExecutionReport fixture(String lang) throws Exception {
        List<Evidence> evidence=Arrays.asList(new Evidence(false,"MEASUREMENT",44L,"Synthetic measurement","/care-journey?tab=measurements&patientId=7&measurementId=44"),new Evidence(true,"SECRET",99L,"SECRET-TITLE","https://secret.test"));
        CurrentAction a=action("  =instruction",evidence,true),b=action("Safe 中文−42",Collections.emptyList(),false);
        PeriodEvent e=new PeriodEvent(a.getLatestReceipt(),10,11,12L,2,a.getPlanTitle(),a.getInstructions(),a.getInstruction(),"SUBMITTED","ACTIVE",true);
        PeriodEvent plan=new PeriodEvent(new EventSummary(106,32,"PLAN_CLOSED","Synthetic doctor","DOCTOR",null,null,"\r\n@closed",null,NOW.minusSeconds(1000),null,Collections.emptyList()),20,21,null,1,"Synthetic closed","旧版本原文",null,null,"COMPLETED",false);
        Question q=new Question(40,"QUESTION-ONLY","ANSWERED","保留描述","原文答复","后续安排","Synthetic recorder",31L,"2026-09-01 09:00:00",null,"2026-09-02 10:00:00");
        return report(lang,"Synthetic patient",Arrays.asList(a,b),Arrays.asList(e,plan),"AVAILABLE",Arrays.asList(q));
    }
    private static CareExecutionReport report(String lang,String patient,List<CurrentAction> actions,List<PeriodEvent> events,String availability,List<Question> questions) throws Exception {
        CareExecutionReportContracts.Request request=CareExecutionReportContracts.parse("{\"patientId\":7,\"fromDate\":\"2026-09-05\",\"toDate\":\"2026-10-04\",\"timeZone\":\"Asia/Shanghai\",\"language\":\""+lang+"\"}",false,NOW);
        long open=actions.stream().filter(a->"OPEN".equals(a.getStatus())).count(),help=actions.stream().filter(a->"NEEDS_HELP".equals(a.getStatus())).count(),submitted=actions.stream().filter(a->"SUBMITTED".equals(a.getStatus())).count(),confirmed=actions.stream().filter(a->"CONFIRMED".equals(a.getStatus())).count();
        Map<String,Long> counts=new LinkedHashMap<>();for(PeriodEvent e:events)counts.merge(e.getEventType(),1L,Long::sum);
        return new CareExecutionReport(new Patient(7,patient),new Scope(null,"ALL_PLANS"),new Metadata(request,NOW.minusSeconds(1),NOW),new CurrentSummary(open,help,submitted,confirmed,0,0),actions,actions,new ActivitySummary(events.size(),events.stream().filter(e->e.getActionId()!=null).map(PeriodEvent::getActionId).distinct().count(),counts),events,availability,questions);
    }
}
