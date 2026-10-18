package org.familyhealthcare.service;

import org.familyhealthcare.util.DataScopeHelper;
import com.alibaba.fastjson2.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;
import static org.familyhealthcare.util.ExportLocalization.text;

@Service
public class FamilyBackupService {
    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    @Autowired private org.familyhealthcare.service.visitquestion.VisitQuestionProjection questionProjection;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.familyhealthcare.service.archivestock.ArchiveStockNotificationSchema stockNotificationSchema;
    @Autowired private org.familyhealthcare.service.archivestock.ArchiveStockNotificationRepository stockNotificationPolicies;
    @Autowired private org.familyhealthcare.service.workflow.HouseholdWorkflowSchema householdSchema;
    @org.springframework.beans.factory.annotation.Value("${medication-execution.enabled:false}") private boolean medicationEnabled;
    @org.springframework.beans.factory.annotation.Value("${stock-workflow.enabled:false}") private boolean stockEnabled;
    @org.springframework.beans.factory.annotation.Value("${family-assistance.enabled:false}") private boolean familyEnabled;
    @org.springframework.beans.factory.annotation.Value("${emergency-assistance.enabled:false}") private boolean emergencyEnabled;
    @org.springframework.beans.factory.annotation.Value("${visit-followup.enabled:false}") private boolean visitFollowUpEnabled;
    private static final JSONObject HOUSEHOLD_NATIVE=householdNative();
    private static JSONObject householdNative() {
        try(InputStream in=FamilyBackupService.class.getResourceAsStream("/household-workflow-schema.json")) {
            if(in==null)throw new IOException("Missing workflow contract");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
            return JSON.parseObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).getJSONObject("native");
        }catch(IOException invalid){throw new IllegalStateException("Unreadable workflow contract",invalid);}
    }
    /** 一次完整结构检查同时判别旧归档表与p15；不把关flag或读取失败当成无跟踪历史。 */
    private ArchiveStorage archiveStorage() {
        org.familyhealthcare.service.workflow.HouseholdWorkflowSchema.Compatibility proof=householdSchema.inspectCompatibility();
        org.familyhealthcare.service.workflow.HouseholdWorkflowSchema.Profile profile=proof==null?org.familyhealthcare.service.workflow.HouseholdWorkflowSchema.Profile.INCOMPATIBLE:proof.getProfile();
        if(profile==org.familyhealthcare.service.workflow.HouseholdWorkflowSchema.Profile.ABSENT&&!medicationEnabled&&!stockEnabled&&!familyEnabled&&!emergencyEnabled&&!visitFollowUpEnabled)return new ArchiveStorage(false,false);
        if(profile==org.familyhealthcare.service.workflow.HouseholdWorkflowSchema.Profile.EXACT_OLD)return new ArchiveStorage(true,false);
        if(profile==org.familyhealthcare.service.workflow.HouseholdWorkflowSchema.Profile.FULLY_INSTALLED||profile==org.familyhealthcare.service.workflow.HouseholdWorkflowSchema.Profile.FULLY_INSTALLED_REMINDER)return new ArchiveStorage(true,true);
        throw new org.familyhealthcare.service.workflow.WorkflowException(503,"SCHEMA_NOT_READY","Household workflow storage is not ready.");
    }
    private boolean householdStorage(){return archiveStorage().modern;}
    private static final class ArchiveStorage {final boolean modern,visits;ArchiveStorage(boolean modern,boolean visits){this.modern=modern;this.visits=visits;}}
    @Autowired private DataScopeHelper scope;
    @Autowired private CareMembershipService membership;
    // 这里只归档列出的家庭记录，账号、授权和协作历史不在其中，不能当作整库备份。
    static final List<String> TABLES=Arrays.asList("patient","patient_clinical","patient_health_target","medication","dialysis_record","medical_record","medical_record_item","medical_record_attachment","dry_weight_monthly","bp_self_monitor_record","bp_pattern_analysis","nutrition_diary","nutrition_assessment","complication_record","ai_analysis_record","health_analysis_automation","alert_rule","alert_record","medication_reminder","medication_log","medication_intake","dialysis_schedule","health_event","care_item","medication_stock","medication_stock_movement","care_intake_action");

    private static final Set<String> WORKFLOW_ALERT_COLUMNS=new HashSet<>(Arrays.asList("workflow_version","workflow_state","workflow_mode","cycle_no","source_revision","metric_key","responsible_user_id","due_at","accepted_by","accepted_at","lock_version","current_handling_event_id","current_feedback_event_id","source_review_required","reassignment_required","classification_json","closed_by","closed_at","closure_reason"));
    private static final Set<String> WORKFLOW_TABLES=new HashSet<>(Arrays.asList("alert_event","alert_source_identity","alert_source_revision","alert_source_link","alert_evidence","alert_command","measurement_command","alert_notification"));

    @Transactional(readOnly=true) public byte[] exportArchive() throws IOException {
        ArchiveStorage storage=archiveStorage();boolean modern=storage.modern;
        org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope medical=medicalValidity.open();
        List<Long> pids=new ArrayList<>();
        for(Long patientId:scope.accessiblePatientIds(scope.requireUserId())) {
            try { scope.requirePatientAccess(patientId,null,false); pids.add(patientId); }
            catch(IllegalStateException denied) { /* A module-only/revoked grant cannot export the full family record. */ }
        }
        JSONObject root=new JSONObject();root.put("format","family-health-backup");root.put("version",1);root.put("createdAt",LocalDateTime.now().toString());
        JSONObject tables=new JSONObject();root.put("tables",tables);List<String>warnings=new ArrayList<>();root.put("warnings",warnings);
        root.put("includedTables",TABLES);
        warnings.add(text("This is a limited version-1 family-record archive, not a full platform backup. Consultations, care-journey records, doctor workspaces, collaborative care plans, measurement follow-up history, source snapshots, revisions, actions, receipts, events, evidence, nurse assignments, notification outbox, commands, access grants and system accounts are not included. Only database-stored attachment content is included. Back up the complete database and attachment storage separately to preserve and restore collaboration history.",
                "此文件为版本1限定家庭记录归档，并非全平台备份。不包含远程问诊、照护全流程、医生工作台、协作计划、测量随访历史、来源快照、版本、行动项、回执、事件、证据、护理分配、通知发件箱、命令、授权和系统账号；附件仅包含已存入数据库的内容。保留及恢复协作历史需另行备份完整数据库和附件存储。"));
        warnings.add(stockNotificationWarning());
        warnings.add(text("Collaborative medication execution, stock reconciliation, family assistance and emergency response history, owned orders, reminders, intakes and their actions are omitted. Independent personal reminders restore disabled and unverified, without a live workflow or source binding.","本归档省略协作用药执行、库存核对、家庭交接及紧急响应历史，以及归属的医嘱、提醒、服药任务和行动。独立个人提醒恢复后关闭且未经核验，不绑定有效工作流或来源。"));
        warnings.add(text("Question copies contain ordinary recorded text only. Professional answers, managed states, ownership, commands, history and modern visit links are omitted; restored questions are OPEN and unverified. When tracking is unavailable, copies are preserved native snapshots and may not reflect current managed text.","问题副本仅包含普通记录文字，不包含专业答复、跟踪状态、所有权、命令、历史或现代就诊关联；恢复后为未关闭、未经核验的独立记录。问题跟踪不可用时，副本为保留的原生快照，可能不代表当前跟踪文字。"));
        warnings.add(text("Managed calendar appointments, clinician appointments, visit records, attendance facts, dispositions, successor bindings and their events or commands are not included. Activity copies are omitted. Restored old appointments are independent historical records and do not establish verified attendance or professional authorship.","本归档不包含已纳管日历预约、医生预约、就诊记录、到诊事实、诊后决定、后继关联及其事件或命令；活动副本也不包含。恢复的旧预约为独立历史记录，不具有已核实到诊或专业归属资格。"));
        warnings.add(text("Common care goals and their history are not included in this limited version-1 archive and cannot be restored from it.","共同照护目标及其历史不包含在本限定版本1归档中，不能从本归档恢复。"));
        warnings.add(text("Managed medical records, their items and attachments, correction history and reference bindings are omitted. This archive cannot restore that history. Legacy records restore as independent unverified copies.","已纳管病历及其检查项、附件、更正历史和引用绑定均不包含；本归档不能恢复这些历史。旧病历恢复为未经核验的独立副本。"));
        Set<String>present=presentTables();
        long omittedAlerts=0;if(medical.ready()&&present.contains("alert_record"))for(int start=0;start<pids.size();start+=100){List<Long> batch=pids.subList(start,Math.min(start+100,pids.size()));omittedAlerts+=jdbc.queryForObject("SELECT COUNT(*) FROM alert_record n WHERE n.patient_id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+") AND EXISTS ("+omittedMedicalAlert("n")+")",Long.class,batch.toArray());}
        if(omittedAlerts>0)warnings.add(text("Omitted "+omittedAlerts+" legacy indicator alerts whose managed source items are omitted. This is not a complete history backup.","另省略 "+omittedAlerts+" 条引用已省略纳管检查项的旧指标告警；本文件不是完整历史备份。"));
        long omittedVisitCopies=0;List<Object> recordIds=new ArrayList<>();Set<Long> questionPatients=new LinkedHashSet<>();
        for(String table:TABLES){if(!present.contains(table))continue;List<Map<String,Object>>rows;
            if(table.equals("medical_record_item")||table.equals("medical_record_attachment"))rows=selectIn(table,"record_id",recordIds,modern,medical,storage.visits);
            else rows=selectIn(table,table.equals("patient")?"id":"patient_id",new ArrayList<Object>(pids),modern,medical,storage.visits);
            if(table.equals("care_item"))rows.addAll(questionCopies(pids,questionPatients));
            if(table.equals("medical_record"))for(Map<String,Object>r:rows)recordIds.add(r.get("id"));
            for(Map<String,Object> row:rows){Map<String,Object> normalized=new LinkedHashMap<>();row.forEach((key,value)->normalized.put(key.toLowerCase(Locale.ROOT),value));row.clear();row.putAll(normalized);}
            if(table.equals("health_event")){int before=rows.size();rows.removeIf(r->"VISIT_RECORD".equals(r.get("source_type")));omittedVisitCopies+=before-rows.size();}
            if(table.equals("care_item")&&modern)omitTrackedOrders(rows);
            if(table.equals("care_item"))rows.removeIf(r->"ACTIVITY".equals(r.get("kind"))||containsHouseholdLink(r.get("data_json"))||restrictedCarePayload(r.get("data_json")));
            for(Map<String,Object>r:rows){
                JSONObject nativeTable=HOUSEHOLD_NATIVE.getJSONObject(table);
                if(nativeTable!=null)r.keySet().removeIf(key->nativeTable.getJSONObject("columns").containsKey(key.toLowerCase(Locale.ROOT)));
                if(table.equals("alert_record"))r.keySet().removeIf(key->WORKFLOW_ALERT_COLUMNS.contains(key.toLowerCase(Locale.ROOT)));
                for(Map.Entry<String,Object>cell:r.entrySet()){Object v=cell.getValue();if(v instanceof Timestamp)cell.setValue(((Timestamp)v).toLocalDateTime().toString().replace('T',' '));else if(v instanceof java.sql.Date)cell.setValue(v.toString());else if(v instanceof java.sql.Clob){try{java.sql.Clob text=(java.sql.Clob)v;cell.setValue(text.getSubString(1,(int)text.length()));}catch(java.sql.SQLException e){throw new IOException("NonemethodreadAttachmentcontent",e);}}}
                if(table.equals("medical_record_attachment")){
                    boolean embedded=r.get("file_content")!=null&&!r.get("file_content").toString().trim().isEmpty();
                    // Database paths are not a trusted file-read capability. Never read the server filesystem here.
                    r.put("file_path",embedded?"inline://"+r.get("record_id")+"/"+r.get("file_name"):null);
                    if(!embedded) warnings.add(text("Attachment content is not stored in the database and was not included: ",
                            "附件内容未存入数据库，本次未包含：")+r.get("file_name"));
                }
            }tables.put(table,rows);
        }
        if(omittedVisitCopies>0)warnings.add(text("Omitted "+omittedVisitCopies+" visit-summary event copies whose original VISIT_RECORD is not included. This is not a complete clinical-history backup.","已省略 "+omittedVisitCopies+" 条原就诊记录不在本包中的就诊摘要事件副本；本文件不是完整临床历史备份。"));
        clearOmittedAppointmentLinks(tables);
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("family-health.json"));zip.write(root.toJSONString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}for(Long patient:questionPatients)finishQuestionCopy(scope.requireUserId(),patient);return out.toByteArray();
    }
    /** 只清掉本归档未保留的同患者预约引用；普通说明文字与独立旧预约原样保存。 */
    private static void clearOmittedAppointmentLinks(JSONObject tables) {
        JSONArray rows=tables.getJSONArray("care_item");if(rows==null)return;Set<String> included=new HashSet<>();
        for(Object item:rows){Map<?,?> row=(Map<?,?>)item;if("APPOINTMENT".equals(row.get("kind")))included.add(row.get("patient_id")+":"+row.get("id"));}
        for(Object item:rows){Map row=(Map)item;String key="QUESTION".equals(row.get("kind"))?"appointmentId":"APPOINTMENT".equals(row.get("kind"))?"nextAppointmentId":null;if(key==null||row.get("data_json")==null)continue;
            JSONObject data=JSON.parseObject(String.valueOf(row.get("data_json")));if(data!=null&&data.get(key)!=null&&!included.contains(row.get("patient_id")+":"+data.get(key))){data.remove(key);row.put("data_json",data.toJSONString());}}
    }
    /** 问题按主键分批投影，不先取出完整 JSON 再删除专业或受限字段。 */
    @SuppressWarnings("unchecked") private List<Map<String,Object>> questionCopies(List<Long> patients,Set<Long> disclosedPatients){
        List<Map<String,Object>> result=new ArrayList<>();long actor=scope.requireUserId();
        for(Long patient:patients){
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM care_item WHERE patient_id=? AND kind='QUESTION'",Long.class,patient);if(count==null||count==0)continue;
            String cursor=null;do{
                Map<String,Object> page=questionPage(actor,patient,cursor);List<Long> ids=new ArrayList<>();for(org.familyhealthcare.entity.CareItem note:(List<org.familyhealthcare.entity.CareItem>)page.get("items"))ids.add(note.getId());
                for(org.familyhealthcare.entity.CareItem note:org.familyhealthcare.service.visitquestion.VisitQuestionFailure.expose(()->questionProjection.batch(actor,patient,ids))){
                    JSONObject ordinary=ordinaryQuestion(note.getDataJson());
                    Map<String,Object> copy=new LinkedHashMap<>();copy.put("id",note.getId());copy.put("patient_id",patient);copy.put("user_id",actor);copy.put("kind","QUESTION");copy.put("title",note.getTitle());copy.put("status","OPEN");copy.put("data_json",ordinary.toJSONString());copy.put("actor_id",null);copy.put("actor_name",null);copy.put("assigned_user_id",null);result.add(copy);disclosedPatients.add(patient);
                }
                cursor=(String)page.get("nextCursor");
            }while(cursor!=null);finishQuestionCopy(actor,patient);
        }return result;
    }
    private Map<String,Object> questionPage(long actor,long patient,String cursor){return org.familyhealthcare.service.visitquestion.VisitQuestionFailure.expose(()->questionProjection.page(actor,patient,cursor,50));}
    private void finishQuestionCopy(long actor,long patient){org.familyhealthcare.service.visitquestion.VisitQuestionFailure.expose(()->{questionProjection.finish(actor,patient);return null;});}
    private Set<String> presentTables(){return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Set<String>>)connection->{Set<String>names=new HashSet<>();try(java.sql.ResultSet rows=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})){while(rows.next()){String name=rows.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT);if(TABLES.contains(name))names.add(name);}}return names;});}
    private List<Map<String,Object>>selectIn(String table,String column,List<Object>ids,boolean modern,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope medical,boolean visits){
        List<Map<String,Object>> out=new ArrayList<>();
        // IN 参数有界；仅导出固定表，不把任何外部表名当成 SQL 能力。
        for(int start=0;start<ids.size();start+=100) {
            List<Object> batch=ids.subList(start,Math.min(start+100,ids.size()));
            String filter="alert_record".equals(table)?" AND (n.alert_type IS NULL OR n.alert_type<>'MEASUREMENT_FOLLOW_UP')":"";
            if("alert_record".equals(table)&&medical.ready())filter+=" AND NOT EXISTS ("+omittedMedicalAlert("n")+")";
            if("medical_record".equals(table))filter+=" AND "+medical.untrackedSql("n.");
            if("care_item".equals(table))filter+=" AND n.kind<>'QUESTION'";
            if("care_item".equals(table)&&visits)filter+=" AND NOT EXISTS(SELECT 1 FROM visit_followup_state vf WHERE vf.source_kind='CARE_ITEM_APPOINTMENT' AND vf.patient_id=n.patient_id AND vf.care_item_id=n.id AND n.kind='APPOINTMENT')";
            if(modern) {
                if("medication".equals(table))filter+=" AND NOT ("+trackedMedicine("n")+")";
                if(Arrays.asList("medication_log","medication_reminder","medication_intake","medication_stock","medication_stock_movement").contains(table))filter+=" AND NOT EXISTS(SELECT 1 FROM medication m WHERE m.id=n.medication_id AND m.patient_id=n.patient_id AND ("+trackedMedicine("m")+"))";
                if(Arrays.asList("medication_reminder","medication_intake","medication_stock","medication_stock_movement","care_intake_action").contains(table))filter+=" AND n.workflow_version=0";
                if("medication_reminder".equals(table))filter+=" AND n.execution_id IS NULL AND n.reconciliation_id IS NULL AND n.order_id IS NULL";
                if("medication_intake".equals(table))filter+=" AND n.execution_id IS NULL AND n.reconciliation_id IS NULL AND n.latest_action_id IS NULL";
                if("medication_stock".equals(table))filter+=" AND n.current_work_id IS NULL";
                if("medication_stock_movement".equals(table))filter+=" AND n.stock_id IS NULL AND n.stock_event_id IS NULL AND n.count_id IS NULL AND n.action_id IS NULL";
                if("care_intake_action".equals(table))filter+=" AND n.execution_id IS NULL AND n.execution_event_id IS NULL AND n.original_action_id IS NULL AND NOT EXISTS(SELECT 1 FROM medication_intake i JOIN medication m ON m.id=i.medication_id AND m.patient_id=i.patient_id WHERE i.id=n.intake_id AND i.patient_id=n.patient_id AND (i.workflow_version<>0 OR i.execution_id IS NOT NULL OR "+trackedMedicine("m")+"))";
                if("care_item".equals(table))filter+=" AND n.family_workflow_version=0 AND n.family_assistance_id IS NULL AND n.medication_workflow_version=0 AND n.medication_execution_id IS NULL AND n.medication_reconciliation_id IS NULL";
            }
            out.addAll(jdbc.queryForList("SELECT n.* FROM `"+table+"` n WHERE n.`"+column+"` IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")"+filter+" ORDER BY n.id",batch.toArray()));
        }return out;
    }
    /** 旧 ORDER 没有 ownership 列也不能带出已省略药品的复制指令；按患者和主键有界核对。 */
    private void omitTrackedOrders(List<Map<String,Object>> rows){
        Map<Long,Set<Long>> selected=new LinkedHashMap<>();
        for(Map<String,Object> row:rows)if("ORDER".equals(row.get("kind"))){Long medication=orderMedicine(row.get("data_json"));if(medication!=null&&row.get("patient_id") instanceof Number)selected.computeIfAbsent(((Number)row.get("patient_id")).longValue(),p->new LinkedHashSet<>()).add(medication);}
        Set<String> safe=new HashSet<>();for(Map.Entry<Long,Set<Long>> group:selected.entrySet()){List<Long> ids=new ArrayList<>(group.getValue());for(int start=0;start<ids.size();start+=100){List<Long> batch=ids.subList(start,Math.min(start+100,ids.size()));List<Object> args=new ArrayList<>();args.add(group.getKey());args.addAll(batch);for(Long id:jdbc.queryForList("SELECT m.id FROM medication m WHERE m.patient_id=? AND m.id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+") AND NOT ("+trackedMedicine("m")+")",Long.class,args.toArray()))safe.add(group.getKey()+":"+id);}}
        rows.removeIf(row->"ORDER".equals(row.get("kind"))&&!safe.contains(row.get("patient_id")+":"+orderMedicine(row.get("data_json"))));
    }
    private static Long orderMedicine(Object value){try{if(value instanceof java.sql.Clob){java.sql.Clob c=(java.sql.Clob)value;value=c.getSubString(1,(int)c.length());}JSONObject data=value instanceof JSONObject?(JSONObject)value:JSON.parseObject(String.valueOf(value));Long id=data==null?null:data.getLong("medicationId");return id!=null&&id>0?id:null;}catch(Exception invalid){return null;}}
    private static String trackedMedicine(String alias) {
        return alias+".workflow_version<>0 OR "+alias+".execution_id IS NOT NULL OR EXISTS(SELECT 1 FROM medication_stock hs WHERE hs.medication_id="+alias+".id AND hs.patient_id="+alias+".patient_id AND (hs.workflow_version<>0 OR hs.current_work_id IS NOT NULL)) OR EXISTS(SELECT 1 FROM medication_intake hi WHERE hi.medication_id="+alias+".id AND hi.patient_id="+alias+".patient_id AND (hi.workflow_version<>0 OR hi.execution_id IS NOT NULL OR hi.reconciliation_id IS NOT NULL))";
    }
    private static boolean restrictedCarePayload(Object value) {
        if(value==null)return false;
        Object parsed=value;if(value instanceof String){String text=((String)value).trim();if(!text.startsWith("{")&&!text.startsWith("["))return false;parsed=JSON.parse(text);}
        if(parsed instanceof Map)for(Map.Entry<?,?> e:((Map<?,?>)parsed).entrySet()) {
            String key=String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
            if(key.contains("psychological")||key.contains("minor")||key.contains("restricted")||key.contains("private"))return true;
            if(restrictedCarePayload(e.getValue()))return true;
        }
        else if(parsed instanceof Collection)for(Object item:(Collection<?>)parsed)if(restrictedCarePayload(item))return true;
        return false;
    }
    private static boolean containsHouseholdLink(Object value) {
        if(value==null)return false;
        if(value instanceof java.sql.Clob)try{java.sql.Clob c=(java.sql.Clob)value;value=c.getSubString(1,(int)c.length());}catch(java.sql.SQLException unreadable){throw new IllegalArgumentException(text("Archive workflow links could not be verified.","归档关联暂不可核实。"),unreadable);}
        Object parsed=value;
        if(value instanceof String){String text=((String)value).trim();if(!text.startsWith("{")&&!text.startsWith("["))return false;parsed=JSON.parse(text);}
        if(parsed instanceof Map)for(Map.Entry<?,?> e:((Map<?,?>)parsed).entrySet()) {
            String key=String.valueOf(e.getKey()).replace("_","").toLowerCase(Locale.ROOT);
            if(key.startsWith("visitfollowup")||key.startsWith("visitdisposition")||Arrays.asList("visitstateid","originappointmentstateid","currentfacteventid","currentdecisioneventid","dispositionrevision","boundafteractioneventid","originboundversion","originboundfingerprint","successorboundversion","successorboundfingerprint","reminderdeliverylogid","attendanceorigin","actualtimekind","sourcefingerprint").contains(key))return true;
            if(Arrays.asList("sourcekind","originkind","successorkind").contains(key)&&Arrays.asList("CARE_ITEM_APPOINTMENT","CARE_APPOINTMENT","VISIT_RECORD").contains(String.valueOf(e.getValue())))return true;
            if(Arrays.asList("questionworkflowversion","questioncommand","commandkey","currentanswereventid","questionversion","questionid","workflowversion","familyworkflowversion","medicationworkflowversion","executionid","medicationexecutionid","reconciliationid","medicationreconciliationid","familyassistanceid","stockworkid","currentworkid","stockeventid","executioneventid","emergencyassistanceid","followupstate","followupkind").contains(key)||containsHouseholdLink(e.getValue()))return true;
        }
        else if(parsed instanceof Collection)for(Object item:(Collection<?>)parsed)if(containsHouseholdLink(item))return true;
        return false;
    }

    /** 只闭合本次已省略的同患者真实病历检查项引用，不按孤立数字猜测来源。 */
    private static String omittedMedicalAlert(String alias){return "SELECT 1 FROM medical_record_item mi JOIN medical_record mr ON mr.id=mi.record_id JOIN medical_record_correction_state ms ON ms.record_id=mr.id WHERE "+alias+".alert_type='INDICATOR' AND "+alias+".source_type='MEDICAL_RECORD_ITEM' AND mi.id="+alias+".source_id AND mr.patient_id="+alias+".patient_id AND ms.patient_id=mr.patient_id";}

    private static boolean containsMedicalTracking(Object value) {
        if(value instanceof String){String raw=((String)value).trim();if(raw.startsWith("{")||raw.startsWith("["))value=JSON.parse(raw);}
        if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
            String key=String.valueOf(entry.getKey()).replace("_","").toLowerCase(Locale.ROOT);
            if(Arrays.asList("managed","validity","correctionstate","correctionhistory","referenceversion","referencekind","referenceid","sourceitemid","boundat","historysequence","currenthistorysequence","baselinecontentversion","currentcontentversion","contentdigest","snapshothash","payloadhash","snapshotjson","commandkey","medicalrecordcorrectionstate","medicalrecordcorrectionhistory","medicalrecordcommand","medicalrecordreferenceversion").contains(key))return true;
            if(!Arrays.asList("importfactsjson","airawresult").contains(key)&&containsMedicalTracking(entry.getValue()))return true;
        }else if(value instanceof Collection)for(Object item:(Collection<?>)value)if(containsMedicalTracking(item))return true;
        return false;
    }

    public JSONObject readArchive(byte[] bytes) throws IOException {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(!"family-health.json".equals(entry.getName()))continue;
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[]buffer=new byte[8192];int n;while((n=zip.read(buffer))!=-1){if(out.size()+n>200*1024*1024)throw new IllegalArgumentException("decompressafter  backupcopyexceed200MB, Please by Family Membersplitbackupcopy");out.write(buffer,0,n);}
            JSONObject root=JSON.parseObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            if(!"family-health-backup".equals(root.getString("format"))||root.getIntValue("version")!=1||root.getJSONObject("tables")==null)throw new IllegalArgumentException("backupcopyformat or versionthis not receivesupport");
            if(root.getJSONObject("tables").getJSONArray("patient")==null)throw new IllegalArgumentException("backupcopymissingFamily Memberrecord");validateWorkflowOmissions(root.getJSONObject("tables"));return root;
        }}throw new IllegalArgumentException("not Yeshas valid familyhealthbackupcopyfile");
    }
    /** A version-1 archive cannot reconstruct workflow aggregates, history or command identities. */
    private void validateWorkflowOmissions(JSONObject tables) {
        for(String table:tables.keySet()) {
            if(!TABLES.contains(table)||WORKFLOW_TABLES.contains(table.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("This limited archive cannot restore collaborative workflow tables or partial history.");
            JSONArray rows=tables.getJSONArray(table);if(rows==null)throw new IllegalArgumentException("Invalid archive table.");
            JSONObject nativeTable=HOUSEHOLD_NATIVE.getJSONObject(table);
            for(Object item:rows) {
                if(!(item instanceof JSONObject))throw new IllegalArgumentException("Invalid archive row.");
                JSONObject row=(JSONObject)item;
                for(String key:row.keySet())if(!key.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("归档字段名必须使用原始规范拼写。");
                if("health_event".equals(table)&&"VISIT_RECORD".equals(row.get("source_type")))throw new IllegalArgumentException(text("This archive cannot restore the unsupported VISIT_RECORD summary reference without its original visit. The archive is unchanged; no records were written.","本归档不支持缺少原就诊记录的 VISIT_RECORD 摘要引用。原包未改动，未写入任何记录。"));
                if("medication_stock".equals(table)&&containsStockPolicy(row))throw new IllegalArgumentException("Stock notification policy metadata cannot be imported from an archive.");
                if(containsMedicalTracking(row))throw new IllegalArgumentException("此归档不能恢复病历更正历史或有效引用绑定。");
                for(String key:row.keySet())if(nativeTable!=null&&nativeTable.getJSONObject("columns").containsKey(key.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Workflow ownership and historical metadata cannot be restored from this limited archive.");
                if(containsHouseholdLink(row))throw new IllegalArgumentException("Workflow links cannot be remapped into live records.");
                if("medication_stock_movement".equals(table)&&row.getString("source_key")!=null&&row.getString("source_key").toUpperCase(Locale.ROOT).matches("^(CORRECTION|REPLENISHMENT):.*"))throw new IllegalArgumentException("Partial workflow ledger history cannot be restored.");
            }
        }
        for(String table:Arrays.asList("alert_record","bp_self_monitor_record")) {
            JSONArray rows=tables.getJSONArray(table);if(rows==null)continue;
            for(Object item:rows) {
                if(!(item instanceof JSONObject))throw new IllegalArgumentException("Invalid clinical archive row.");
                JSONObject row=(JSONObject)item;
                for(String key:row.keySet()) {
                    String column=key.toLowerCase(Locale.ROOT);
                    if(WORKFLOW_ALERT_COLUMNS.contains(column)||Arrays.asList("source_identity_id","source_status","correction_reason","snapshot_json").contains(column)||("alert_type".equals(column)&&"MEASUREMENT_FOLLOW_UP".equalsIgnoreCase(String.valueOf(row.get(key)).trim())))
                        throw new IllegalArgumentException("Measurement follow-up rows and metadata cannot be restored from a legacy archive.");
                }
            }
        }
    }

    private static boolean containsStockPolicy(Object value) {
        if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
            String key=String.valueOf(entry.getKey()).replace("_","").toLowerCase(Locale.ROOT);
            if(key.startsWith("archivestocknotification")||Arrays.asList("enabled","controlversion","enabledafterstockversion","restoredat","notificationpolicy","notificationenabled","reviewacknowledged","currentfactshash","expectedcontrolversion","policyversion","policyenabled","policy","notificationstate","notificationpermission","reviewerid","receipt","resultjson").contains(key)||containsStockPolicy(entry.getValue()))return true;
        } else if(value instanceof Collection)for(Object child:(Collection<?>)value)if(containsStockPolicy(child))return true;
        return false;
    }
    private static String stockNotificationWarning(){return text("Stock reminders for newly restored copies are OFF and require separate per-item review before enabling. Historical copies restored before this release cannot be identified reliably and are not automatically covered.","本次新恢复副本的库存提醒默认关闭，需逐项核对后单独启用。此版本之前已恢复的历史库存无法可靠识别，不会被自动纳入此保护。");}
    private static List<Object> currentWarnings(JSONObject root){List<Object> warnings=new ArrayList<>();Object old=root.get("warnings");if(old instanceof Collection)warnings.addAll((Collection<?>)old);String current=stockNotificationWarning();if(!warnings.contains(current))warnings.add(current);return warnings;}
    public Map<String,Object> preview(byte[]bytes)throws IOException {JSONObject root=readArchive(bytes);Map<String,Object>out=new LinkedHashMap<>();Map<String,Integer>counts=new LinkedHashMap<>();for(String table:TABLES){JSONArray rows=root.getJSONObject("tables").getJSONArray(table);if(rows!=null)counts.put(table,rows.size());}out.put("createdAt",root.get("createdAt"));out.put("counts",counts);out.put("warnings",currentWarnings(root));out.put("patients",root.getJSONObject("tables").getJSONArray("patient"));return out;}

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,rollbackFor=Exception.class) public Map<String,Object> restore(byte[]bytes)throws IOException {
        Long uid=scope.requireUserId();JSONObject root=readArchive(bytes),tables=root.getJSONObject("tables");validateMedicalAlertSources(tables);householdStorage();medicalValidity.open();Set<String>present=presentTables();
        for(String table:TABLES)if(tables.getJSONArray(table)!=null&&!tables.getJSONArray(table).isEmpty()&&!present.contains(table))throw new IllegalArgumentException("datadatabasemissingtable "+table+", Please first increaseleveldatadatabaseafter restore");
        boolean hasStock=tables.getJSONArray("medication_stock")!=null&&!tables.getJSONArray("medication_stock").isEmpty();
        if(hasStock){stockNotificationPolicies.requireRestoreTransaction(jdbc);stockNotificationSchema.requireReady(jdbc);}
        Map<String,Map<String,Long>>ids=new HashMap<>();Map<String,Integer>counts=new LinkedHashMap<>();
        for(String table:TABLES){JSONArray rows=tables.getJSONArray(table);if(rows==null)continue;Map<String,Long>mapping=new HashMap<>();ids.put(table,mapping);int count=0;
            for(Object obj:rows){JSONObject raw=(JSONObject)obj;Map<String,Object>row=new LinkedHashMap<>(raw);Object oldId=row.remove("id");
                if(oldId==null||mapping.containsKey(oldId.toString()))throw new IllegalArgumentException("backupcopyincludeduplicate or missingID: "+table);
                if(!Arrays.asList("patient","medical_record_item","medical_record_attachment").contains(table))remap(row,"patient_id","patient",ids,true);
                remap(row,"medication_id","medication",ids,false);remap(row,"record_id","medical_record",ids,table.equals("medical_record_item")||table.equals("medical_record_attachment"));
                remap(row,"related_dialysis_id","dialysis_record",ids,false);remap(row,"dialysis_record_id","dialysis_record",ids,false);remap(row,"completed_record_id","dialysis_record",ids,false);
                remap(row,"reminder_id","medication_reminder",ids,false);remap(row,"intake_id","medication_intake",ids,false);
                remap(row,"rule_id","alert_rule",ids,false);
                for(String userColumn:Arrays.asList("user_id","actor_id","assigned_user_id"))if(row.containsKey(userColumn)&&row.get(userColumn)!=null)row.put(userColumn,uid);
                if(table.equals("patient")){row.put("user_id",uid);row.put("name",String.valueOf(row.get("name"))+" (restoresecondarythis ) ");row.put("deleted",0);}
                // 恢复的是历史副本，先关提醒和自动分析、取消待处理服药任务并停止有效处方，避免旧安排再次触发。
                if(table.equals("medication_reminder")){row.put("enabled",0);if(row.containsKey("last_trigger_at"))row.put("last_trigger_at",null);}
                if(table.equals("health_analysis_automation")) {
                    row.put("enabled",0);
                    row.put("config_version",0);
                    for(String field:Arrays.asList("active_run_id","next_run_at","last_run_at","last_run_status","last_error","notification_channel_ids","last_analysis_record_id")) row.put(field,null);
                }
                if(table.equals("medication_intake")&&Arrays.asList("PENDING","SNOOZED","MISSED").contains(row.get("status")))row.put("status","CANCELLED");
                if(table.equals("care_item")){
                    if("QUESTION".equals(row.get("kind"))){
                        row.put("status","OPEN");row.put("actor_id",null);row.put("actor_name",null);row.put("assigned_user_id",null);
                        JSONObject note=ordinaryQuestion(row.get("data_json"));note.remove("appointmentId");row.put("data_json",note.toJSONString());
                    }
                    if("ORDER".equals(row.get("kind"))&&Arrays.asList("ACTIVE","SCHEDULED").contains(row.get("status")))row.put("status","STOPPED");
                    row.put("notify_at",null);row.put("notified_at",null);row.put("escalated_at",null);
                }
                if(table.equals("medical_record")){row.put("content_version",1L);row.put("verification_status","REVIEW_REQUIRED");row.put("verified_by",null);row.put("verified_at",null);row.put("verification_note",null);}
                if(table.equals("alert_record")&&"MEDICAL_RECORD_ITEM".equals(row.get("source_type")))remap(row,"source_id","medical_record_item",ids,true);
                if(table.equals("medical_record_attachment")) {
                    boolean embedded=row.get("file_content")!=null&&!row.get("file_content").toString().trim().isEmpty();
                    row.put("file_path",embedded?"inline://"+row.get("record_id")+"/"+row.get("file_name"):null);
                }
                if(table.equals("medication_stock_movement")&&row.get("source_key")!=null){String key=row.get("source_key").toString();String[]parts=key.split(":",2);if(parts.length==2){String target=parts[0].equals("INTAKE")?"medication_intake":"medication_log";Long mapped=ids.getOrDefault(target,Collections.emptyMap()).get(parts[1]);row.put("source_key",mapped==null?null:parts[0]+":"+mapped);}else row.put("source_key",null);}
                Number generated=new SimpleJdbcInsert(jdbc).withTableName(table).usingColumns(row.keySet().toArray(new String[0])).usingGeneratedKeyColumns("id").executeAndReturnKey(row);
                if(table.equals("medication_stock"))stockNotificationPolicies.createRestoredOff(generated.longValue());
                mapping.put(oldId.toString(),generated.longValue());count++;
            }counts.put(table,count);
        }
        // JSON links may point to later rows, so rewrite them after all identities are available.
        JSONArray careRows=tables.getJSONArray("care_item");if(careRows!=null)for(Object obj:careRows){JSONObject raw=(JSONObject)obj;String json=raw.getString("data_json");if(json==null)continue;JSONObject d=JSON.parseObject(json);
            if("QUESTION".equals(raw.get("kind"))){
                d=ordinaryQuestion(json);Object oldAppointment=d.remove("appointmentId");
                if(oldAppointment!=null)for(Object candidate:careRows){JSONObject appointment=(JSONObject)candidate;
                    if("APPOINTMENT".equals(appointment.get("kind"))&&Objects.equals(String.valueOf(appointment.get("id")),String.valueOf(oldAppointment))&&Objects.equals(raw.get("patient_id"),appointment.get("patient_id"))){
                        Long mapped=ids.getOrDefault("care_item",Collections.emptyMap()).get(String.valueOf(oldAppointment));if(mapped!=null)d.put("appointmentId",mapped);break;
                    }
                }
                jdbc.update("UPDATE care_item SET data_json=? WHERE id=?",d.toJSONString(),ids.get("care_item").get(raw.get("id").toString()));continue;
            }
            remapJson(d,"medicationId","medication",ids);remapJson(d,"reportId","medical_record",ids);remapJson(d,"appointmentId","care_item",ids);remapJson(d,"nextAppointmentId","care_item",ids);remapJson(d,"sourceId","care_item",ids);remapJson(d,"intakeId","medication_intake",ids);
            if(d.getJSONArray("reminderIds")!=null){List<Long>mapped=new ArrayList<>();for(Object id:d.getJSONArray("reminderIds")){Long n=ids.getOrDefault("medication_reminder",Collections.emptyMap()).get(id.toString());if(n!=null)mapped.add(n);}d.put("reminderIds",mapped);}
            if(d.containsKey("escalationUserId"))d.put("escalationUserId",null);
            jdbc.update("UPDATE care_item SET data_json=? WHERE id=?",d.toJSONString(),ids.get("care_item").get(raw.get("id").toString()));
        }
        Map<String,Object>out=new LinkedHashMap<>();out.put("counts",counts);out.put("patientIds",ids.getOrDefault("patient",Collections.emptyMap()).values());out.put("warnings",currentWarnings(root));out.put("stockNotificationMessage",stockNotificationWarning());out.put("message",text("Managed medical records and their dependent alert history are not restored by this limited archive. Appointment attendance facts, visit records, professional authorship, dispositions and successor history are not restored; old appointments remain independent unverified historical records. Restored as independent unverified copies; collaborative medication, stock, family, emergency and measurement follow-up history was omitted. Raw readings require explicit adoption for a follow-up. Medication reminders and automated analysis are disabled; verify care plans, configure notification channels and invite caregivers again before enabling them.", "本限定归档不恢复纳管病历及其依赖告警历史。预约到诊事实、就诊记录、专业归属、诊后决定与后继历史均未恢复；旧预约仅为未经核验的独立历史记录。已恢复为未经核验的独立副本，不包含协作用药、库存、家庭交接、紧急响应及测量随访历史；原始测量需明确纳入随访。用药提醒与自动分析均已关闭；请核对照护计划、重新配置通知渠道并邀请照护成员后再启用。"));return out;
    }
    /** 恢复前核对归档内的原始患者链，不能让新ID映射掩盖跨患者引用。 */
    private void validateMedicalAlertSources(JSONObject tables) {
        JSONArray alerts=tables.getJSONArray("alert_record");if(alerts==null)return;
        Map<String,JSONObject> patients=null,records=null,items=null;
        for(Object value:alerts){JSONObject alert=(JSONObject)value;if(!"INDICATOR".equals(alert.get("alert_type"))||!"MEDICAL_RECORD_ITEM".equals(alert.get("source_type")))continue;
            if(patients==null){patients=archiveRows(tables.getJSONArray("patient"));records=archiveRows(tables.getJSONArray("medical_record"));items=archiveRows(tables.getJSONArray("medical_record_item"));}
            String patient=alert.getString("patient_id");JSONObject item=items.get(alert.getString("source_id"));JSONObject record=item==null?null:records.get(item.getString("record_id"));
            if(patient==null||!patients.containsKey(patient)||record==null||!patient.equals(record.getString("patient_id")))throw new IllegalArgumentException(text("A medical-item alert has a missing or inconsistent archived patient relationship.","病历检查项告警的归档患者关联缺失或不一致。"));
        }
    }
    private Map<String,JSONObject> archiveRows(JSONArray rows) {
        Map<String,JSONObject> result=new HashMap<>();if(rows!=null)for(Object value:rows){JSONObject row=(JSONObject)value;String id=row.getString("id");if(id==null||result.put(id,row)!=null)throw new IllegalArgumentException(text("The archive contains a missing or duplicate identity.","归档包含缺失或重复的记录标识。"));}return result;
    }
    private void remap(Map<String,Object>row,String column,String table,Map<String,Map<String,Long>>ids,boolean required){if(!row.containsKey(column)||row.get(column)==null){if(required)throw new IllegalArgumentException("backupcopymissingFamily Memberrelated");return;}Long mapped=ids.getOrDefault(table,Collections.emptyMap()).get(row.get(column).toString());if(mapped==null&&required)throw new IllegalArgumentException("backupcopyin Family Memberrelateddoes not exist");row.put(column,mapped);}
    private void remapJson(JSONObject d,String key,String table,Map<String,Map<String,Long>>ids){if(d.get(key)!=null)d.put(key,ids.getOrDefault(table,Collections.emptyMap()).get(d.get(key).toString()));}
    /** 限定归档只恢复普通文字；现代就诊、专业身份和历史必须另行完整备份。 */
    private static JSONObject ordinaryQuestion(Object json) {
        JSONObject raw=json==null?new JSONObject():JSON.parseObject(String.valueOf(json)),out=new JSONObject();
        for(String key:Arrays.asList("description","answer","followUp"))if(raw.get(key) instanceof String)out.put(key,raw.get(key));
        Object appointment=raw.get("appointmentId");if(appointment instanceof Number&&((Number)appointment).longValue()>0&&appointment.toString().matches("[1-9][0-9]*"))out.put("appointmentId",appointment);
        return out;
    }
}
