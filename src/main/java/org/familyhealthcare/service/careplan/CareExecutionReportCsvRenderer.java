package org.familyhealthcare.service.careplan;

import org.familyhealthcare.service.careplan.CareExecutionReport.*;
import org.familyhealthcare.service.careplan.CareExecutionReportContracts.Format;
import org.familyhealthcare.service.careplan.CareExecutionReportHtmlRenderer.Labels;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Two fixed row models. No questions, evidence bodies, extra metadata rows, or database reads. */
@Component
public final class CareExecutionReportCsvRenderer {
    private static final List<Column> COMMON=columns(
        "schema|Report schema version|报告结构版本", "generated|Generated at (UTC)|生成时间（UTC）", "asOf|Current state as of (UTC)|当前状态读取时间（UTC）",
        "patient|Patient ID|患者ID", "scopeCode|Scope code|范围码", "scopeLabel|Scope|范围", "scopePlan|Scope plan ID|范围计划ID",
        "from|From date|开始日期", "to|To date (inclusive)|结束日期（含当日）", "zone|Time zone|时区", "start|Range start (UTC)|期间开始（UTC）",
        "end|Range end exclusive (UTC)|期间结束（不含，UTC）", "language|Language|界面语言", "source|Source code|来源码",
        "completeCode|Completeness code|完整性码", "completeLabel|Completeness|完整性", "safety|CSV text safety|CSV文本安全");
    private static final List<Column> ACTION=columns(
        "plan|Plan ID|计划ID", "revision|Published revision ID|已发布版本ID", "revisionNo|Published revision number|已发布版本号", "action|Action ID|事项ID",
        "title|Plan title|计划标题", "instructions|Published plan instructions|已发布计划说明", "instruction|Action instruction|事项指示", "assignee|Assigned user ID|负责人用户ID",
        "availableCode|Assignee available code|负责人可用码", "availableLabel|Assignee availability|负责人可用性", "due|Due at (UTC)|期限（UTC）",
        "statusCode|Current status code|当前状态码", "statusLabel|Current status|当前状态", "overdueCode|Overdue code|逾期码", "overdueLabel|Overdue|逾期",
        "supplementCode|Needs supplement code|待补充码", "supplementLabel|Needs supplement|待补充", "waiting|Review waiting since (UTC)|本次待复核起点（UTC）", "evidence|Evidence references|证据引用");
    private static final List<Column> EVENT=columns(
        "plan|Plan ID|计划ID", "revision|Published revision ID|已发布版本ID", "revisionNo|Published revision number|已发布版本号", "action|Action ID|事项ID",
        "title|Plan title|计划标题", "instructions|Original published instructions|原发布版本说明", "instruction|Original action instruction|原事项指示",
        "statusCode|Historical status after event code|事件后历史状态码", "statusLabel|Historical status after event|事件后的历史状态",
        "lifecycleCode|Plan lifecycle at generation code|生成时计划生命周期码", "lifecycleLabel|Plan lifecycle at generation|生成时计划生命周期",
        "currentCode|Revision is current at generation code|生成时是否当前版本码", "currentLabel|Revision is current at generation|生成时是否当前版本");
    private static final List<Column> SUMMARY=columns(
        "id|Event ID|事件ID", "typeCode|Event type code|事件类型码", "typeLabel|Event type|事件类型", "actor|Actor ID|操作者ID",
        "name|Actor name (historical)|操作者姓名（历史快照）", "roleCode|Actor role code (historical)|操作者角色码（历史快照）", "roleLabel|Actor role (historical)|操作者角色（历史快照）",
        "relationCode|Actor relation code (historical)|操作者关系码（历史快照）", "relationLabel|Actor relation (historical)|操作者关系（历史快照）",
        "modeCode|Entry mode code|代录方式码", "modeLabel|Entry mode|代录方式", "kindCode|Administrative follow-up kind code|行政跟进类别码", "kindLabel|Administrative follow-up kind|行政跟进类别",
        "recorded|Recorded at (UTC)|记录时间（UTC）", "occurred|Actual execution at (UTC)|实际执行时间（UTC）", "note|Original note|原文备注", "evidence|Evidence references|证据引用");
    private static final String[] PREFIX={"receipt","return","review","help","follow"};
    private static final String[] PREFIX_EN={"Latest receipt","Latest return","Latest doctor review","Latest difficulty","Latest administrative follow-up"};
    private static final String[] PREFIX_ZH={"最近提交","最近退回","最近医生复核","最近困难","最近行政跟进"};
    private static final List<Column> ACTION_COLUMNS=actionColumns();
    private static final List<Column> EVENT_COLUMNS=eventColumns();

    public byte[] render(CareExecutionReport report,Format format,CareExecutionReportBudget budget) {
        Objects.requireNonNull(report,"report");Objects.requireNonNull(budget,"budget");budget.checkTime();
        if(format!=Format.ACTIONS_CSV&&format!=Format.EVENTS_CSV)throw CareExecutionReportException.invalid();
        Labels labels=new Labels(report.getMetadata().getLanguage());
        List<Column> schema=format==Format.ACTIONS_CSV?ACTION_COLUMNS:EVENT_COLUMNS;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try {
            Writer writer=new OutputStreamWriter(budget.output(out),StandardCharsets.UTF_8);
            writer.write('\uFEFF');List<Object> header=new ArrayList<>();for(Column column:schema)header.add(labels.t(column.en,column.zh));writeRow(writer,header,budget);
            if(format==Format.ACTIONS_CSV) {
                for(CurrentAction a:report.getCurrentActions()) {
                    Map<String,Object> row=common(report,labels);source(row,a.getPlanId(),a.getRevisionId(),a.getRevisionNo(),a.getActionId(),a.getPlanTitle(),a.getInstructions(),a.getInstruction());
                    row.put("assignee",a.getAssignedUserId());row.put("availableCode",Boolean.toString(a.isAssigneeAvailable()));row.put("availableLabel",labels.t(a.isAssigneeAvailable()?"Available":"Helper is no longer available",a.isAssigneeAvailable()?"可用":"协助人已不可用"));row.put("due",a.getDueAt());
                    pair(row,"status",a.getStatus(),labels);row.put("overdueCode",Boolean.toString(a.isOverdue()));row.put("overdueLabel",labels.yes(a.isOverdue()));row.put("supplementCode",Boolean.toString(a.isNeedsSupplement()));row.put("supplementLabel",labels.yes(a.isNeedsSupplement()));row.put("waiting",a.getReviewWaitingSince());
                    row.put("evidence",evidence(a.getEvidence(),report,labels));
                    EventSummary[] summaries={a.getLatestReceipt(),a.getLatestReturn(),a.getLatestReview(),a.getLatestHelp(),a.getLatestFollowUp()};
                    for(int i=0;i<summaries.length;i++)if(summaries[i]!=null)summary(row,PREFIX[i]+".",summaries[i],report,labels);
                    writeRow(writer,values(schema,row),budget);
                }
            } else {
                for(PeriodEvent e:report.getPeriodEvents()) {
                    Map<String,Object> row=common(report,labels);source(row,e.getPlanId(),e.getRevisionId(),e.getRevisionNo(),e.getActionId(),e.getPlanTitle(),e.getInstructions(),e.getActionInstruction());
                    pair(row,"status",e.getActionStatusAfterEvent(),labels);pair(row,"lifecycle",e.getPlanLifecycleAtGeneration(),labels);row.put("currentCode",Boolean.toString(e.isRevisionIsCurrentAtGeneration()));row.put("currentLabel",labels.yes(e.isRevisionIsCurrentAtGeneration()));
                    summary(row,"event.",new EventSummary(e.getEventId(),e.getActorId(),e.getEventType(),e.getActorName(),e.getActorRole(),e.getActorRelation(),e.getEntryMode(),e.getNote(),e.getFollowUpKind(),e.getRecordedAt(),e.getOccurredAt(),e.getEvidence()),report,labels);
                    writeRow(writer,values(schema,row),budget);
                }
            }
            writer.flush();budget.checkTime();return out.toByteArray();
        } catch(IOException e) {throw new CareExecutionReportException(CareExecutionReportException.Code.REPORT_RENDER_UNAVAILABLE);}
    }

    private static Map<String,Object> common(CareExecutionReport r,Labels l) {
        Metadata m=r.getMetadata();Map<String,Object> row=new HashMap<>();row.put("schema",r.getReportSchemaVersion());row.put("generated",m.getGeneratedAt());row.put("asOf",m.getCurrentAsOf());row.put("patient",r.getPatient().getId());
        pair(row,"scope",r.getScope().getLabel(),l);row.put("scopePlan",r.getScope().getPlanId());row.put("from",m.getFromDate());row.put("to",m.getToDate());row.put("zone",m.getTimeZone());row.put("start",m.getRangeStartAt());row.put("end",m.getRangeEndExclusiveAt());row.put("language",m.getLanguage());row.put("source","CARE_PLAN");
        pair(row,"complete",r.getCompleteness(),l);row.put("safety",l.t(Labels.CSV_SAFETY_EN,Labels.CSV_SAFETY_ZH));return row;
    }
    private static void source(Map<String,Object> row,long plan,long revision,int revisionNo,Long action,String title,String instructions,String instruction){row.put("plan",plan);row.put("revision",revision);row.put("revisionNo",revisionNo);row.put("action",action);row.put("title",title);row.put("instructions",instructions);row.put("instruction",instruction);}
    private static void pair(Map<String,Object> row,String key,String code,Labels labels){row.put(key+"Code",code);row.put(key+"Label",code==null||code.isEmpty()?null:labels.code(code));}
    private static void summary(Map<String,Object> row,String prefix,EventSummary e,CareExecutionReport report,Labels labels){row.put(prefix+"id",e.getEventId());pair(row,prefix+"type",e.getEventType(),labels);row.put(prefix+"actor",e.getActorId());row.put(prefix+"name",e.getActorName());pair(row,prefix+"role",e.getActorRole(),labels);pair(row,prefix+"relation",e.getActorRelation(),labels);pair(row,prefix+"mode",e.getEntryMode(),labels);pair(row,prefix+"kind",e.getFollowUpKind(),labels);row.put(prefix+"recorded",e.getRecordedAt());row.put(prefix+"occurred",e.getOccurredAt());row.put(prefix+"note",e.getNote());row.put(prefix+"evidence",evidence(e.getEvidence(),report,labels));}
    private static String evidence(List<Evidence> refs,CareExecutionReport report,Labels labels) {
        StringJoiner result=new StringJoiner("\n");
        for(Evidence e:refs) {
            if(e.isRestricted()){result.add(labels.t("Restricted evidence exists","存在受限证据"));continue;}
            StringBuilder text=new StringBuilder();text.append(e.getSourceType()==null?"":e.getSourceType()).append(" | ").append(labels.code(e.getSourceType())).append(" | ").append(e.getSourceId()==null?"":e.getSourceId()).append(" | ").append(e.getTitle()==null?"":e.getTitle());
            String path=CareExecutionReportHtmlRenderer.safePath(e,report.getPatient().getId());if(path!=null)text.append(" | ").append(path);result.add(text.toString());
        }
        return result.toString();
    }
    private static List<Object> values(List<Column> schema,Map<String,Object> row){List<Object> result=new ArrayList<>(schema.size());for(Column column:schema)result.add(row.get(column.key));return result;}
    private static void writeRow(Writer writer,List<Object> values,CareExecutionReportBudget budget)throws IOException {
        for(int column=0;column<values.size();column++) {
            budget.checkTime();if(column>0)writer.write(',');Object value=values.get(column);
            if(value instanceof Number){writer.write(value.toString());continue;}
            String text=value==null?"":value.toString();writer.write('"');if(dangerous(text))writer.write('\'');
            int start=0;
            for(int i=0;i<text.length();i++) {
                if(text.charAt(i)=='"'){writer.write(text,start,i-start);writer.write("\"\"");start=i+1;}
                else if(i-start>=4096){writer.write(text,start,i-start);start=i;budget.checkTime();}
            }
            writer.write(text,start,text.length()-start);writer.write('"');
        }
        writer.write("\r\n");
    }
    /** Check Unicode whitespace, format controls and ISO controls before a spreadsheet formula prefix. */
    private static boolean dangerous(String value) {
        boolean leadingControl=false;
        for(int offset=0;offset<value.length();) {
            int cp=value.codePointAt(offset);offset+=Character.charCount(cp);
            if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT){leadingControl=true;continue;}
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp))continue;
            return leadingControl||cp=='='||cp=='+'||cp=='-'||cp=='@';
        }
        return leadingControl;
    }
    private static List<Column> actionColumns(){List<Column> result=new ArrayList<>(COMMON);result.addAll(ACTION);for(int i=0;i<PREFIX.length;i++)for(Column c:SUMMARY)result.add(new Column(PREFIX[i]+"."+c.key,PREFIX_EN[i]+" / "+c.en,PREFIX_ZH[i]+" / "+c.zh));return Collections.unmodifiableList(result);}
    private static List<Column> eventColumns(){List<Column> result=new ArrayList<>(COMMON);result.addAll(EVENT);for(Column c:SUMMARY)result.add(new Column("event."+c.key,c.en,c.zh));return Collections.unmodifiableList(result);}
    private static List<Column> columns(String... definitions){List<Column> result=new ArrayList<>();for(String definition:definitions){String[] parts=definition.split("\\|",-1);result.add(new Column(parts[0],parts[1],parts[2]));}return Collections.unmodifiableList(result);}
    private static final class Column {final String key,en,zh;Column(String key,String en,String zh){this.key=key;this.en=en;this.zh=zh;}}
}
