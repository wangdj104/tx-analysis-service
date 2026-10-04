package org.familyhealthcare.service.careplan;

import org.familyhealthcare.service.careplan.CareExecutionReport.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Fixed local XHTML. It consumes an authorized projection and never loads clinical data or resources. */
@Component
public final class CareExecutionReportHtmlRenderer {
    public static final class Html {
        private final String markup;
        private final List<String> visibleText;
        private Html(String markup, List<String> visibleText) {
            this.markup = markup;
            this.visibleText = Collections.unmodifiableList(new ArrayList<>(visibleText));
        }
        public String getMarkup() { return markup; }
        public List<String> getVisibleText() { return visibleText; }
    }

    public Html render(CareExecutionReport report, CareExecutionReportBudget budget) {
        Objects.requireNonNull(report, "report"); Objects.requireNonNull(budget, "budget"); budget.checkTime();
        Labels labels = new Labels(report.getMetadata().getLanguage());
        Page p = new Page(labels, report, budget);
        p.raw("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"");p.raw(labels.zh ? "zh-CN" : "en");
        p.raw("\"><head><meta charset=\"UTF-8\"/><title>");p.escaped(labels.t("Care execution report","照护执行报告"));
        p.raw("</title><style>body{font-family:sans-serif;color:#17212b;line-height:1.45;margin:20px}h1{font-size:24px}h2{font-size:19px}h3{font-size:16px}table{border-collapse:collapse;width:100%;margin:10px 0}th,td{border:1px solid #ccd3d9;padding:7px;vertical-align:top;text-align:left;white-space:pre-wrap;word-wrap:break-word;overflow-wrap:anywhere}th{background:#eef2f5}thead{display:table-header-group}a{color:#174c7d}p{white-space:pre-wrap;word-wrap:break-word}h2,h3{page-break-after:avoid}@page{size:A4;margin:16mm}@media print{body{margin:0}thead{display:table-header-group}tr{page-break-inside:avoid}a{text-decoration:none}}</style></head><body>");
        p.heading(1,"Care execution report","照护执行报告");
        p.paragraph("Current state as of this generation; activity covers the selected period.","当前状态截至本次生成；活动范围为所选期间。");
        p.table();p.field("Patient ID","患者ID",report.getPatient().getId());p.field("Patient","患者",report.getPatient().getDisplayName());
        p.field("Scope","范围",labels.code(report.getScope().getLabel()));p.field("Scope plan ID","范围计划ID",report.getScope().getPlanId());
        Metadata m=report.getMetadata();p.field("Report schema version","报告结构版本",report.getReportSchemaVersion());
        p.field("Generated at","生成时间",p.time(m.getGeneratedAt()));p.field("Current state as of","当前状态读取时间",p.time(m.getCurrentAsOf()));
        p.field("From date","开始日期",m.getFromDate());p.field("To date (inclusive)","结束日期（含当日）",m.getToDate());
        p.field("Time zone","时区",m.getTimeZone());p.field("Range start","期间开始",p.time(m.getRangeStartAt()));p.field("Range end (exclusive)","期间结束（不含）",p.time(m.getRangeEndExclusiveAt()));
        p.field("Language","界面语言",m.getLanguage());p.field("Completeness","完整性",labels.code(report.getCompleteness()));p.endTable();

        p.heading(2,"Current state and counting basis","当前状态与统计口径");
        CurrentSummary s=report.getCurrentSummary();if(s==null)throw inconsistent();
        p.paragraph("N contains all current actions in active published plans in this scope. The four states are mutually exclusive. Overdue and needs-supplement are subsets. Period activity does not use N as its denominator.","N为本范围内当前有效已发布计划的全部事项。四种状态互斥，逾期和待补充为子集。期间活动不使用N作为分母。");
        p.table();p.field("Current actions (N)","当前事项（N）",s.getTotal());
        p.field("To do or supplement","待执行或补充",p.ratio(s.getOpen(),s.getTotal()));p.field("Needs help","遇到困难",p.ratio(s.getNeedsHelp(),s.getTotal()));
        p.field("Submitted awaiting doctor review","已提交待医生复核",p.ratio(s.getSubmitted(),s.getTotal()));p.field("Reviewed by doctor","医生已复核",p.ratio(s.getConfirmed(),s.getTotal()));
        p.field("Overdue (subset)","逾期（子集）",s.getOverdue());p.field("Needs supplement (subset)","待补充（子集）",s.getNeedsSupplement());p.endTable();
        if(s.getTotal()==0)p.paragraph("No current active-plan actions. Ratios: Not applicable.","无当前有效计划事项。比例：不适用。");
        p.paragraph("Submitted actions await doctor review and are not patient-overdue. Doctor review records processing; it does not establish treatment effectiveness. Administrative follow-up does not resolve difficulty or confirm a receipt.","已提交事项等待医生复核，不计为患者逾期。医生复核表示记录已处理，不证明治疗有效。行政跟进不自动解决困难或确认回执。");

        p.heading(2,"Current attention","当前待处理");
        p.paragraph("Current difficulty, supplement, review-waiting and overdue items are not filtered by the activity period.","当前困难、待补充、待复核及逾期事项不受活动期间筛选限制。");
        if(report.getCurrentAttention().isEmpty())p.paragraph("No current attention items","无当前待处理事项");
        for(CurrentAction a:report.getCurrentAttention()) {
            p.heading(3,"Action","事项",a.getActionId());p.table();p.source(a.getPlanId(),a.getRevisionId(),a.getRevisionNo(),a.getActionId(),a.getPlanTitle());p.state(a);
            p.field("Due at","期限",p.time(a.getDueAt()));p.field("Review waiting since","本次待复核起点",p.time(a.getReviewWaitingSince()));p.endTable();
            p.summary("Latest difficulty","最近困难",a.getLatestHelp());p.summary("Latest return","最近退回",a.getLatestReturn());p.summary("Latest administrative follow-up","最近行政跟进",a.getLatestFollowUp());
        }

        p.heading(2,"Current actions and sources","当前事项及来源");
        if(report.getCurrentActions().isEmpty())p.paragraph("No current active-plan actions","无当前有效计划事项");
        for(CurrentAction a:report.getCurrentActions()) {
            p.heading(3,"Action","事项",a.getActionId());p.table();p.source(a.getPlanId(),a.getRevisionId(),a.getRevisionNo(),a.getActionId(),a.getPlanTitle());
            p.field("Published plan instructions","已发布计划说明",a.getInstructions());p.field("Action instruction","事项指示",a.getInstruction());
            p.field("Assigned user ID","负责人用户ID",a.getAssignedUserId());p.field("Assignee availability","负责人可用性",labels.t(a.isAssigneeAvailable()?"Available":"Helper is no longer available",a.isAssigneeAvailable()?"可用":"协助人已不可用"));
            p.field("Due at","期限",p.time(a.getDueAt()));p.state(a);p.field("Review waiting since","本次待复核起点",p.time(a.getReviewWaitingSince()));
            if(a.getReviewWaitingSince()!=null)p.field("Review wait duration (seconds)","本次待复核时长（秒）",java.time.Duration.between(a.getReviewWaitingSince(),m.getCurrentAsOf()).getSeconds());
            p.endTable();p.evidence(a.getEvidence());
            p.summary("Latest receipt","最近提交",a.getLatestReceipt());p.summary("Latest return","最近退回",a.getLatestReturn());p.summary("Latest doctor review","最近医生复核",a.getLatestReview());
            p.summary("Latest difficulty","最近困难",a.getLatestHelp());p.summary("Latest administrative follow-up","最近行政跟进",a.getLatestFollowUp());
        }

        p.heading(2,"Period activity","期间活动");
        p.paragraph("Events are selected by recorded time. Actual execution time is separate and does not change the activity period. Historical event state and current plan/revision markers are separate.","事件仅按记录时间筛选。实际执行时间单独显示，不改变活动所属期间。事件后的历史状态与当前计划、版本标记分列。");
        ActivitySummary activity=report.getActivitySummary();if(activity==null)throw inconsistent();
        p.table();p.field("Public event count","公开事件数",activity.getEventCount());p.field("Distinct actions involved","涉及去重事项数",activity.getDistinctActionCount());
        for(String code:CareExecutionReportContracts.PUBLIC_EVENT_TYPES)p.field(labels.code(code)+" ("+code+")",labels.code(code)+"（"+code+"）",activity.getEventTypeCounts().getOrDefault(code,0L));p.endTable();
        if(report.getPeriodEvents().isEmpty())p.paragraph("No public events in this period","本期间无公开事件");
        for(PeriodEvent e:report.getPeriodEvents()) {
            p.heading(3,"Event","事件",e.getEventId());p.table();p.source(e.getPlanId(),e.getRevisionId(),e.getRevisionNo(),e.getActionId(),e.getPlanTitle());
            p.field("Original published instructions","原发布版本说明",e.getInstructions());p.field("Original action instruction","原事项指示",e.getActionInstruction());
            p.field("Historical status after event","事件后的历史状态",p.enumValue(e.getActionStatusAfterEvent()));p.field("Plan lifecycle at generation","生成时计划生命周期",p.enumValue(e.getPlanLifecycleAtGeneration()));
            p.field("Revision is current at generation","生成时是否当前版本",labels.yes(e.isRevisionIsCurrentAtGeneration()));
            p.event(e.getEventId(),e.getEventType(),e.getActorId(),e.getActorName(),e.getActorRole(),e.getActorRelation(),e.getEntryMode(),e.getFollowUpKind(),e.getRecordedAt(),e.getOccurredAt(),e.getNote());p.endTable();p.evidence(e.getEvidence());
        }

        p.heading(2,"Visit questions","就诊问题");
        String available=report.getQuestionsAvailability();
        if("NOT_AUTHORIZED".equals(available))p.paragraph("Questions are not authorized; their content and count were not read.","无就诊问题读取权限；未读取问题正文或数量。");
        else if("NOT_INCLUDED_IN_PLAN_SCOPE".equals(available))p.paragraph("Questions are not included in a single-plan report because they have no reliable plan association.","单计划报告未纳入问题，因现有问题没有可靠的计划关联。");
        else {
            p.paragraph("These are the current contents of editable legacy records, not a complete historical author chain. Recorded answers are not independently verified doctor reviews. Legacy local time has no recorded time zone.","以下为可编辑旧记录的当前内容，不承诺完整历史作者链。记录的答复不等于医生独立核验。旧记录本地时间，时区未记录。");
            if(report.getQuestions().isEmpty())p.paragraph("No questions","无就诊问题");
            for(Question q:report.getQuestions()) {
                p.table();p.field("Question ID","问题ID",q.getId());p.field("Title","标题",q.getTitle());p.field("Question status","问题状态",labels.question(q.getStatus()));
                p.field("Original status code","原状态码",q.getStatus());p.field("Description","描述",q.getDescription());p.field("Recorded answer","记录的答复",q.getAnswer());p.field("Follow-up arrangement","后续安排",q.getFollowUp());
                p.field("Recorder ID","记录人ID",q.getActorId());p.field("Recorder name","记录人姓名",q.getActorName());p.field("Created local time","创建本地时间",q.getCreatedAtLocal());p.field("Updated local time","更新本地时间",q.getUpdatedAtLocal());p.field("Event local time","事件本地时间",q.getEventAtLocal());p.field("Time basis","时间依据",labels.code(q.getTimeBasis()));p.endTable();
            }
        }
        p.heading(2,"Evidence restrictions and purpose","证据限制与用途说明");
        p.paragraph("References contain only authorized type, identifier, title and protected in-app location. No source body, measurement value or attachment is included. Restricted or unavailable evidence reveals no identifier, title, value, location or reason. Opening a reference requires current access in its original module.","引用仅含已授权的类型、标识、标题及应用内受保护定位。不包含原模块正文、测量值或附件。受限或不可用证据不透露标识、标题、值、定位或具体原因。打开原模块需重新检查当前权限。");
        p.paragraph("For visit preparation and care coordination. This is a bounded readable export, not a restorable archive or clinical proof. Original clinical text is preserved without automatic translation. Saved or already-delivered copies cannot be recalled after access is revoked.","供就诊准备与照护协作使用。这是有限范围的可读导出，不是可恢复档案或临床证明。原临床文本保留原语言，不自动翻译。授权撤销后无法收回已交付或已保存的副本。");
        p.heading(3,"CSV text safety","CSV文本安全");p.paragraph(Labels.CSV_SAFETY_EN,Labels.CSV_SAFETY_ZH);
        p.raw("</body></html>");budget.checkTime();return new Html(p.markup.toString(),p.visible);
    }

    private static CareExecutionReportException inconsistent() { return new CareExecutionReportException(CareExecutionReportException.Code.REPORT_DATA_INCONSISTENT); }

    /** Shared by the two renderers. Only these exact projection-created relative locations are exported. */
    static String safePath(Evidence e,long patientId) {
        if(e.isRestricted() || e.getSourceId()==null || e.getSourceId()<=0) return null;
        String expected;
        if("MEASUREMENT".equals(e.getSourceType()))expected="/care-journey?tab=measurements&patientId="+patientId+"&measurementId="+e.getSourceId();
        else if("MEDICAL_RECORD".equals(e.getSourceType()))expected="/medical-record?tab=list&patientId="+patientId+"&recordId="+e.getSourceId();
        else return null;
        return expected.equals(e.getDetailPath()) ? expected : null;
    }

    static final class Labels {
        static final String CSV_SAFETY_EN="CSV text cells with formula prefixes after leading whitespace or controls, or leading control characters, are prefixed with an apostrophe. Quoting preserves commas, quotes and line breaks. Source records are unchanged; CSV is not a raw-byte archive. Empty CSV files contain only headers; their scope, time zone, language, version and generation time are shown on the generation page.";
        static final String CSV_SAFETY_ZH="CSV文本单元格在前导空白或控制符后含公式前缀，或以控制字符开头时，会加前置单引号。双引号转义保留逗号、引号和换行。原记录不变，CSV不是原始字节归档。空CSV仅含字段表头；范围、时区、语言、版本及生成时间由生成页展示。";
        final boolean zh;
        Labels(String language) {if(!"en".equals(language)&&!"zh-CN".equals(language))throw CareExecutionReportException.invalid();zh="zh-CN".equals(language);}
        String t(String en,String cn){return zh?cn:en;}
        String yes(boolean value){return value?t("Yes","是"):t("No","否");}
        String code(String code) {
            if(code==null||code.isEmpty())return t("Not applicable","不适用");
            switch(code) {
                case "OPEN":return t("To do or supplement","待执行或补充");case "NEEDS_HELP":return t("Needs help","遇到困难");case "SUBMITTED":return t("Submitted awaiting doctor review","已提交待医生复核");case "CONFIRMED":return t("Reviewed by doctor","医生已复核");
                case "PLAN_PUBLISHED":return t("Plan published","计划已发布");case "REVISION_PUBLISHED":return t("Revision published","修订已发布");case "RECEIPT_SUBMITTED":return t("Receipt submitted","回执已提交");case "HELP_REQUESTED":return t("Help requested","已报告困难");case "FOLLOW_UP_RECORDED":return t("Administrative follow-up recorded","已记录行政跟进");case "RECEIPT_RETURNED":return t("Receipt returned for supplement","回执退回待补充");case "RECEIPT_CONFIRMED":return t("Receipt reviewed by doctor","回执已由医生复核");case "PLAN_CANCELLED":return t("Plan cancelled","计划已取消");case "PLAN_CLOSED":return t("Plan closed","计划已关闭");
                case "ALL_PLANS":return t("All plans","全部计划");case "SINGLE_PLAN":return t("This plan only","仅此计划");case "COMPLETE":return t("Complete for selected scope and authorized sections","所选范围及已授权区域完整");
                case "SELF":return t("Self-reported (declared)","自行记录（声明）");case "ASSISTED":return t("Assisted recording","协助记录");case "CONTACTED":return t("Contacted","已联系");case "AWAITING_INFORMATION":return t("Awaiting information","等待信息");case "DOCTOR_NOTIFIED":return t("Doctor notified","已通知医生");
                case "ACTIVE":return t("Active","有效");case "CANCELLED":return t("Cancelled","已取消");case "CLOSED":return t("Closed","已关闭");
                case "FAMILY":return t("Family","家属");case "DOCTOR":return t("Doctor","医生");case "NURSE":return t("Nurse","护理");case "PATIENT":return t("Patient","患者");case "ADMIN":return t("Administrator","管理员");
                case "OWNER":return t("Record owner","记录所有者");case "SPOUSE":return t("Spouse","配偶");case "PARENT":return t("Parent","父母");case "CHILD":return t("Child","子女");case "OTHER":return t("Other","其他");
                case "MEASUREMENT":return t("Measurement","测量");case "MEDICAL_RECORD":return t("Medical record","病历");case "LEGACY_UNZONED":return t("Legacy local time; time zone not recorded","旧记录本地时间，时区未记录");
                default:return code;
            }
        }
        String question(String status) {
            if(status==null)return t("Unrecognized","未识别");
            switch(status){case "OPEN":return t("To discuss","待讨论");case "ANSWERED":return t("Recorded answer","已记录答复");case "DONE":return t("Done (recorded status)","已完成（记录状态）");case "RESOLVED":return t("Resolved (recorded status)","已解决（记录状态）");case "CANCELLED":return t("Cancelled","已取消");default:return t("Unrecognized","未识别");}
        }
    }

    private static final class Page {
        final StringBuilder markup=new StringBuilder(4096);
        final List<String> visible=new ArrayList<>();final Labels labels;final CareExecutionReport report;final CareExecutionReportBudget budget;
        long bytes;int poll;
        Page(Labels labels,CareExecutionReport report,CareExecutionReportBudget budget){this.labels=labels;this.report=report;this.budget=budget;}
        // An independent intermediate bound. Only the final HTML/PDF representation consumes budget.output().
        void raw(String value) {
            for(int i=0;i<value.length();i++) {
                char c=value.charAt(i);int count=c<0x80?1:c<0x800?2:3;
                if(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1))) {count=4;require(count);markup.append(c).append(value.charAt(++i));}
                else {if(Character.isSurrogate(c))count=1;require(count);markup.append(c);}
                if(++poll>=4096){budget.checkTime();poll=0;}
            }
        }
        void require(int count){if(bytes+count>CareExecutionReportContracts.MAX_OUTPUT_BYTES)throw CareExecutionReportException.limitExceeded("OUTPUT_BYTES",CareExecutionReportContracts.MAX_OUTPUT_BYTES);bytes+=count;}
        void escaped(String value) {
            if(value==null)return;
            int start=0;
            for(int i=0;i<value.length();i++) {
                String replacement;switch(value.charAt(i)){case '&':replacement="&amp;";break;case '<':replacement="&lt;";break;case '>':replacement="&gt;";break;case '"':replacement="&quot;";break;case '\'':replacement="&#39;";break;case '\r':replacement="&#13;";break;default:continue;}
                raw(value.substring(start,i));raw(replacement);start=i+1;
            }
            raw(value.substring(start));
        }
        void text(String value){if(value==null||value.isEmpty())return;visible.add(value);escaped(value);}
        String value(Object value){return value==null||value.toString().isEmpty()?labels.t("Not applicable","不适用"):value.toString();}
        void heading(int level,String en,String cn){raw("<h"+level+">");text(labels.t(en,cn));raw("</h"+level+">");}
        void heading(int level,String en,String cn,long id){heading(level,en+" "+id,cn+" "+id);}
        void paragraph(String en,String cn){raw("<p>");text(labels.t(en,cn));raw("</p>");}
        void table(){raw("<table><thead><tr><th>");text(labels.t("Field","字段"));raw("</th><th>");text(labels.t("Value","内容"));raw("</th></tr></thead><tbody>");}
        void endTable(){raw("</tbody></table>");}
        void field(String en,String cn,Object value){raw("<tr><th scope=\"row\">");text(labels.t(en,cn));raw("</th><td>");text(value(value));raw("</td></tr>");}
        String ratio(long n,long total){return total==0?labels.t("Not applicable","不适用"):n+" / "+total;}
        String time(Instant instant){return instant==null?null:instant.toString()+" | "+DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX '['VV']'",Locale.ROOT).format(instant.atZone(ZoneId.of(report.getMetadata().getTimeZone())));}
        String enumValue(String code){return code==null||code.isEmpty()?null:labels.code(code)+" ("+code+")";}
        void source(long plan,long revision,int no,Long action,String title){field("Source","来源","CARE_PLAN");field("Plan ID","计划ID",plan);field("Published revision ID","已发布版本ID",revision);field("Published revision number","已发布版本号",no);field("Action ID","事项ID",action);field("Plan title","计划标题",title);}
        void state(CurrentAction a){field("Current status","当前状态",enumValue(a.getStatus()));field("Overdue","逾期",labels.yes(a.isOverdue()));field("Needs supplement","待补充",labels.yes(a.isNeedsSupplement()));}
        void summary(String en,String cn,EventSummary e){heading(3,en,cn);if(e==null){paragraph("No corresponding record","无对应记录");return;}table();event(e.getEventId(),e.getEventType(),e.getActorId(),e.getActorName(),e.getActorRole(),e.getActorRelation(),e.getEntryMode(),e.getFollowUpKind(),e.getRecordedAt(),e.getOccurredAt(),e.getNote());endTable();evidence(e.getEvidence());}
        void event(long id,String type,long actor,String name,String role,String relation,String mode,String kind,Instant recorded,Instant occurred,String note){field("Event ID","事件ID",id);field("Event type","事件类型",enumValue(type));field("Actor ID","操作者ID",actor);field("Actor name (historical)","操作者姓名（历史快照）",name);field("Actor role (historical)","操作者角色（历史快照）",enumValue(role));field("Actor relation (historical)","操作者关系（历史快照）",enumValue(relation));field("Entry mode","代录方式",enumValue(mode));field("Administrative follow-up kind","行政跟进类别",enumValue(kind));field("Recorded at","记录时间",time(recorded));if(occurred!=null)field("Actual execution at","实际执行时间",time(occurred));field("Original note","原文备注",note);}
        void evidence(List<Evidence> refs){if(refs.isEmpty())return;heading(3,"Evidence references","证据引用");for(Evidence e:refs){if(e.isRestricted()){paragraph("Restricted evidence exists","存在受限证据");continue;}table();field("Evidence type","证据类型",enumValue(e.getSourceType()));field("Evidence source ID","证据源ID",e.getSourceId());field("Evidence title","证据标题",e.getTitle());String path=safePath(e,report.getPatient().getId());if(path!=null){raw("<tr><th scope=\"row\">");text(labels.t("Protected in-app detail","应用内受保护详情"));raw("</th><td><a href=\"");escaped(path);raw("\">");text(path);raw("</a></td></tr>");}endTable();}}
    }
}
