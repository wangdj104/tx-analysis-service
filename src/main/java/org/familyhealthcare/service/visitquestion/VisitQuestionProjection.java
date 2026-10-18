package org.familyhealthcare.service.visitquestion;

import org.familyhealthcare.entity.CareItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.visitquestion.VisitQuestionReadSupport.*;

/** 旧消费者只收到普通记录；接管来源仍保持 B 排除，专业正文只能从专用详情取得。 */
@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class VisitQuestionProjection {
    private final VisitQuestionQueryService queries;
    private final VisitQuestionRepository repo;
    private final VisitQuestionAuthorizationService auth;
    public VisitQuestionProjection(VisitQuestionQueryService queries,VisitQuestionRepository repo,VisitQuestionAuthorizationService auth){this.queries=queries;this.repo=repo;this.auth=auth;}
    /** 只读提示与旧编辑器会原样提交的 details 分离，避免伪造来源或预览截断回写。 */
    public static final class OrdinaryNote extends CareItem {
        private final Map<String,Object> questionProjection;
        OrdinaryNote(Map<String,Object> metadata){questionProjection=Collections.unmodifiableMap(metadata);}
        public Map<String,Object> getQuestionProjection(){return questionProjection;}
    }
    public static Map<String,Object> metadata(CareItem note){
        return note instanceof OrdinaryNote?((OrdinaryNote)note).getQuestionProjection():Collections.emptyMap();
    }
    private static final class View {
        boolean editorAdmin,qStorage;
        final boolean current,b;final String join,title,description,answer,followUp,appointment,bytes;
        View(boolean current,boolean b){
            this.current=current;this.b=b;join=current?" LEFT JOIN visit_question_state s ON s.question_id=n.id AND s.patient_id=n.patient_id":"";
            title=value("n.title","s.current_title");description=value(field("n","description"),"s.current_description");answer=value(field("n","answer"),"s.current_recorded_answer");followUp=value(field("n","followUp"),"s.current_follow_up");
            appointment=value("CASE WHEN JSON_TYPE(JSON_EXTRACT("+jsonObject("n")+",'$.appointmentId'))='INTEGER' THEN JSON_EXTRACT("+jsonObject("n")+",'$.appointmentId') ELSE NULL END","s.legacy_appointment_id");
            bytes="COALESCE(OCTET_LENGTH("+title+"),0)+COALESCE(OCTET_LENGTH("+description+"),0)+COALESCE(OCTET_LENGTH("+answer+"),0)+COALESCE(OCTET_LENGTH("+followUp+"),0)";
        }
        String value(String nativeValue,String managed){return current?"CASE WHEN s.question_id IS NULL THEN "+nativeValue+" ELSE "+managed+" END":nativeValue;}
        String flags(){return "CASE WHEN "+ordinarySource("n")+" THEN 1 ELSE 0 END AS ordinary_allowed,CASE WHEN "+(current?"((s.question_id IS NULL AND "+marker("n")+" IS NULL) OR (s.question_id IS NOT NULL AND JSON_TYPE("+marker("n")+")='INTEGER' AND "+marker("n")+"=1))":"1=1")+" THEN 1 ELSE 0 END AS identity_valid";}
        String from(){return " FROM care_item n"+join+" WHERE n.patient_id=? AND n.kind='QUESTION' AND "+noB("n",b)+(editorAdmin?" AND "+marker("n")+" IS NULL"+(qStorage?" AND NOT EXISTS(SELECT 1 FROM visit_question_state v WHERE v.question_id=n.id)":""):"");}
        String select(boolean preview){
            String ordinary="n.id,n.patient_id,"+clip(title,preview)+" AS title,"+clip(description,preview)+" AS description,"+clip(answer,preview)+" AS answer,"+clip(followUp,preview)+" AS follow_up,"+appointment+" AS appointment_id,n.status AS legacy_status,n.created_at,n.updated_at,n.event_at,";
            return "SELECT "+ordinary+(current?"s.state,s.lock_version,s.ordinary_answer_origin,s.ordinary_answer_recorder_id,s.ordinary_answer_recorded_at,CASE WHEN s.question_id IS NULL THEN 0 ELSE 1 END AS managed":"NULL AS state,NULL AS lock_version,NULL AS ordinary_answer_origin,NULL AS ordinary_answer_recorder_id,NULL AS ordinary_answer_recorded_at,0 AS managed")+","+flags();
        }
        String clip(String value,boolean preview){return preview?"LEFT("+value+",240)":value;}
    }
    private View view(long actor,long patient){queries.access(actor,patient);boolean current=queries.availability()==VisitQuestionSchema.Availability.READY;if(current)repo.ready();return new View(current,queries.bStorage());}
    private View editingView(long actor,long patient){
        try{return view(actor,patient);}
        catch(org.familyhealthcare.service.workflow.WorkflowException failure){if(failure.getStatus()!=403)throw failure;}
        VisitQuestionContracts.id(actor,"actor");VisitQuestionContracts.id(patient,"patientId");
        auth.ordinaryEditorAdmin(actor,patient);
        VisitQuestionSchema.Status storage=repo.storage();
        if(storage==VisitQuestionSchema.Status.INCOMPATIBLE)throw new org.familyhealthcare.service.workflow.WorkflowException(503,"QUESTION_SCHEMA_NOT_READY","Question ownership cannot be verified.");
        View view=new View(false,queries.bStorage());view.editorAdmin=true;view.qStorage=storage==VisitQuestionSchema.Status.STRUCTURALLY_READY;return view;
    }
    private void finishView(long actor,long patient,View view){if(view.editorAdmin)auth.ordinaryEditorAdmin(actor,patient);else auth.read(actor,patient);}
    public Map<String,Object> page(long actor,long patient,String cursor,Integer requested){return page(actor,patient,cursor,requested,false);}
    private Map<String,Object> page(long actor,long patient,String cursor,Integer requested,boolean editing){
        int limit=VisitQuestionQueryService.pageSize(requested);long after=cursor(cursor,actor,patient,"ORDINARY");View view=editing?editingView(actor,patient):view(actor,patient);
        List<Map<String,Object>> rows=repo.readRows(view.current,true,view.select(true)+view.from()+" AND n.id>? AND "+ordinarySource("n")+" ORDER BY n.id LIMIT ?",patient,after,limit+1);
        boolean more=rows.size()>limit;List<CareItem> items=new ArrayList<>();long last=after;
        for(Map<String,Object> row:rows.subList(0,Math.min(limit,rows.size()))){identity(row);last=id(row.get("id"));items.add(note(row,view.current,true));}
        Map<String,Object> out=map("items",items,"hasMore",more,"nextCursor",more?next(actor,patient,"ORDINARY",last):null,"previews",true,"omissionNotice","Ordinary recorded notes only; professional answers and history require the question detail.");encoded(out,VisitQuestionContracts.RESPONSE_BYTES);finishView(actor,patient,view);return out;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> editingPage(long actor,long patient,String cursor,Integer requested){
        Map<String,Object> page=page(actor,patient,cursor,requested,true);
        List<Long> ids=new ArrayList<>();
        for(CareItem note:(List<CareItem>)page.get("items"))ids.add(note.getId());
        // 旧编辑表单必须取得全文；按最坏 JSON 转义预算先拒绝，不把预览冒充可编辑原文。
        page.put("items",readBatch(actor,patient,ids,VisitQuestionContracts.EVENT_PAGE_BYTES,true));
        page.put("previews",false);
        encoded(page,VisitQuestionContracts.EVENT_PAGE_BYTES);finishEditing(actor,patient,(List<CareItem>)page.get("items"));return page;
    }
    public List<CareItem> batch(long actor,long patient,List<Long> ids){
        return readBatch(actor,patient,ids,0,false);
    }
    private List<CareItem> readBatch(long actor,long patient,List<Long> ids,long encodedBudget,boolean editing){
        if(ids==null||ids.size()>50)throw invalid("Question metadata batches are limited to 50 IDs.");for(Long question:ids)VisitQuestionContracts.id(question,"questionId");if(ids.isEmpty())return Collections.emptyList();
        View view=editing?editingView(actor,patient):view(actor,patient);String where=view.from()+" AND n.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";List<Object> args=new ArrayList<>();args.add(patient);args.addAll(ids);
        List<Map<String,Object>> sizes=repo.readRows(view.current,true,"SELECT n.id,"+view.bytes+" AS bytes,"+view.flags()+where+" ORDER BY n.id",args.toArray());
        List<Long> permitted=new ArrayList<>();List<Long> maxima=new ArrayList<>();long total=0;
        for(Map<String,Object> row:sizes){if(!flag(row,"ordinary_allowed"))continue;identity(row);bytes(row.get("bytes"),VisitQuestionContracts.RAW_BYTES);total+=((Number)row.get("bytes")).longValue();permitted.add(id(row.get("id")));maxima.add(((Number)row.get("bytes")).longValue());}
        if(total>VisitQuestionContracts.EVENT_PAGE_BYTES||(encodedBudget>0&&total*6L+4096L*(permitted.size()+1)>encodedBudget))throw limit();if(permitted.isEmpty()){finishView(actor,patient,view);return Collections.emptyList();}
        args=new ArrayList<>();args.add(patient);args.addAll(permitted);List<String> maximaById=new ArrayList<>();
        for(int index=0;index<permitted.size();index++){
            maximaById.add("WHEN ? THEN ?");args.add(permitted.get(index));args.add(maxima.get(index));
        }
        // 逐行限制预检后的增长；长度表达式只出现一次，50 条也不放大固定 SQL 传输预算。
        where=view.from()+" AND n.id IN ("+String.join(",",Collections.nCopies(permitted.size(),"?"))+") AND "+view.bytes+"<=CASE n.id "+String.join(" ",maximaById)+" ELSE -1 END";
        List<CareItem> out=new ArrayList<>();for(Map<String,Object> row:repo.readRows(view.current,true,view.select(false)+where+" ORDER BY n.id",args.toArray())){identity(row);CareItem note=note(row,view.current,false);encoded(note,VisitQuestionContracts.DETAIL_BYTES);out.add(note);}
        if(out.size()!=permitted.size())throw VisitQuestionRepository.conflict("QUESTION_STATE_CHANGED");if(editing)finishEditing(actor,patient,out);else auth.read(actor,patient);return out;
    }
    private static final class ReportSize {
        final long id,bytes;
        ReportSize(long id,long bytes){this.id=id;this.bytes=bytes;}
    }
    private static String reportBytes(String expression,boolean utf8Function){return "COALESCE("+(utf8Function?"LENGTH(STRINGTOUTF8("+expression+"))":"OCTET_LENGTH("+expression+")")+",0)";}
    /** C2 使用自己的累计源文预算；旧普通正文不能套用交互式 Q 的 128 KiB 限额。 */
    public List<CareItem> report(long actor,long patient,List<Long> ids,org.familyhealthcare.service.careplan.CareExecutionReportBudget budget,boolean utf8Function){
        budget.requireRows("QUESTIONS",ids.size(),org.familyhealthcare.service.careplan.CareExecutionReportContracts.MAX_QUESTIONS);
        for(Long question:ids)VisitQuestionContracts.id(question,"questionId");
        View view=view(actor,patient);List<ReportSize> permitted=new ArrayList<>();long total=0;
        String raw=reportBytes(view.title,utf8Function)+"+"+reportBytes(view.description,utf8Function)+"+"+reportBytes(view.answer,utf8Function)+"+"+reportBytes(view.followUp,utf8Function);
        String managed=view.current?"s.question_id IS NOT NULL":"1=0";
        String status=view.value("n.status","s.state"),origin=view.value("'LEGACY_UNVERIFIED'","s.ordinary_answer_origin");
        String metadata="SELECT n.id,"+raw+" AS bytes,"+reportBytes(status,utf8Function)+"+"+reportBytes(origin,utf8Function)+" AS metadata_bytes,CASE WHEN "+managed+" THEN 1 ELSE 0 END AS managed,"+(view.current?"CASE WHEN s.ordinary_answer_origin='USER_RECORDED' THEN s.ordinary_answer_recorded_at ELSE NULL END":"NULL")+" AS recorded_at,"+view.flags();
        // 报告沿用 C2 的 8 MiB 总预算；交互页的 4 MiB 不能误套到普通报告。
        // 先完成全部至多 200 条的标量预检，再读取任何完整普通正文。
        for(int start=0;start<ids.size();start+=50){
            budget.checkTime();List<Long> batch=ids.subList(start,Math.min(start+50,ids.size()));List<Object> args=new ArrayList<>();args.add(patient);args.addAll(batch);
            for(Map<String,Object> row:repo.readRows(view.current,true,metadata+view.from()+" AND n.id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+") ORDER BY n.id",args.toArray())){
                budget.checkTime();if(!flag(row,"ordinary_allowed"))continue;identity(row);
                long bytes=((Number)row.get("bytes")).longValue(),question=id(row.get("id"));boolean tracked=flag(row,"managed");
                if(tracked)bytes(row.get("bytes"),VisitQuestionContracts.RAW_BYTES);
                String basis=view.current?(tracked?"CURRENT_ORDINARY_NOTE":"LEGACY_UNVERIFIED"):"PRESERVED_NATIVE_SNAPSHOT";
                String link=tracked?"/care?patientId="+patient+"&questionId="+question:"";
                String recorded=(String)row.get("recorded_at");
                total+=bytes+((Number)row.get("metadata_bytes")).longValue()+basis.length()+link.length()+(recorded==null?0:recorded.length());
                budget.requireSourceTextCapacity(total);permitted.add(new ReportSize(question,bytes));
            }
        }
        List<CareItem> out=new ArrayList<>();
        for(int start=0;start<permitted.size();start+=50){
            budget.checkTime();List<ReportSize> batch=permitted.subList(start,Math.min(start+50,permitted.size()));List<Object> args=new ArrayList<>();args.add(patient);for(ReportSize size:batch)args.add(size.id);
            List<String> maxima=new ArrayList<>();for(ReportSize size:batch){maxima.add("WHEN ? THEN ?");args.add(size.id);args.add(size.bytes);}
            String where=view.from()+" AND n.id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+") AND "+raw+"<=CASE n.id "+String.join(" ",maxima)+" ELSE -1 END";
            List<Map<String,Object>> rows=repo.readRows(view.current,true,view.select(false)+where+" ORDER BY n.id",args.toArray());
            if(rows.size()!=batch.size())throw VisitQuestionRepository.conflict("QUESTION_STATE_CHANGED");
            for(Map<String,Object> row:rows){budget.checkTime();identity(row);CareItem note=note(row,view.current,false);if(flag(row,"managed"))encoded(note,VisitQuestionContracts.DETAIL_BYTES);out.add(note);}
        }
        budget.checkTime();auth.read(actor,patient);return out;
    }
    public CareItem exact(long actor,long patient,long question){List<CareItem> out=readBatch(actor,patient,Collections.singletonList(question),0,true);if(out.size()!=1)throw VisitQuestionRepository.denied();return out.get(0);}
    public CareItem locate(long actor,long question){VisitQuestionContracts.id(question,"questionId");Map<String,Object> row=repo.plainOne("SELECT patient_id FROM care_item WHERE id=? AND kind='QUESTION'",question);if(row==null)throw VisitQuestionRepository.denied();return exact(actor,id(row.get("patient_id")),question);}
    /** 报告使用可重复读内容快照，此检查暂停它，避免用旧快照授权最终披露。 */
    @Transactional(propagation=Propagation.NOT_SUPPORTED,readOnly=true) public void finish(long actor,long patient){auth.read(actor,patient);}
    /** 旧上下文在完成其他读取后再核验。管理员只能披露此时仍未接管的普通记录。 */
    @Transactional(propagation=Propagation.NOT_SUPPORTED,readOnly=true)
    public void finishEditing(long actor,long patient,List<CareItem> notes){
        View view=editingView(actor,patient);
        if(view.editorAdmin&&!notes.isEmpty()){
            if(notes.size()>50)throw invalid("Question metadata batches are limited to 50 IDs.");
            List<Object> args=new ArrayList<>();args.add(patient);
            for(CareItem note:notes){if(note.getPatientId()==null||note.getPatientId()!=patient||!"QUESTION".equals(note.getKind()))throw VisitQuestionRepository.denied();VisitQuestionContracts.id(note.getId(),"questionId");args.add(note.getId());}
            List<Map<String,Object>> rows=repo.readRows(false,true,"SELECT n.id"+view.from()+" AND "+ordinarySource("n")+" AND n.id IN ("+String.join(",",Collections.nCopies(notes.size(),"?"))+")",args.toArray());
            if(rows.size()!=notes.size())throw VisitQuestionRepository.denied();
        }
        finishView(actor,patient,view);
    }
    public Map<String,Object> counts(long actor,long patient,LocalDate from,LocalDate to){
        View view=view(actor,patient);
        if(!view.current){auth.read(actor,patient);return map("total",0L,"managed",0L,"closed",0L,"legacy",0L,"available",false);}
        String managed=view.current?"s.question_id IS NOT NULL":"1=0",closed=view.current?"s.state='CLOSED'":"1=0";
        String sql="SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN "+managed+" THEN 1 ELSE 0 END),0) AS managed,COALESCE(SUM(CASE WHEN "+managed+" AND "+closed+" THEN 1 ELSE 0 END),0) AS closed,COALESCE(SUM(CASE WHEN NOT ("+managed+") THEN 1 ELSE 0 END),0) AS legacy FROM care_item n"+view.join+" WHERE n.patient_id=? AND n.kind='QUESTION' AND "+noB("n",view.b)+" AND "+ordinarySource("n")+" AND ((s.question_id IS NULL AND "+marker("n")+" IS NULL) OR (s.question_id IS NOT NULL AND JSON_TYPE("+marker("n")+")='INTEGER' AND "+marker("n")+"=1)) AND CAST(COALESCE(n.event_at,n.created_at) AS DATE) BETWEEN ? AND ?";
        Map<String,Object> out=VisitQuestionQueryService.single(repo.readRows(view.current,true,sql,patient,from.toString(),to.toString()));out.put("available",true);auth.read(actor,patient);return out;
    }
    private static CareItem note(Map<String,Object> row,boolean current,boolean preview){
        Map<String,Object> metadata=new LinkedHashMap<>();
        CareItem out=new OrdinaryNote(metadata);out.setId(id(row.get("id")));out.setPatientId(id(row.get("patient_id")));out.setKind("QUESTION");out.setTitle((String)row.get("title"));out.setStatus(flag(row,"managed")?(String)row.get("state"):(String)row.get("legacy_status"));
        Map<String,Object> details=new LinkedHashMap<>();for(String[] field:new String[][]{{"description","description"},{"answer","answer"},{"followUp","follow_up"}})if(row.get(field[1])!=null)details.put(field[0],row.get(field[1]));
        Object appointment=row.get("appointment_id");if(appointment!=null){try{long id=Long.parseLong(appointment.toString());VisitQuestionContracts.id(id,"appointmentId");details.put("appointmentId",id);}catch(IllegalArgumentException invalid){throw VisitQuestionRepository.conflict("QUESTION_CONTEXT_CHANGED");}}
        String origin=flag(row,"managed")?(String)row.get("ordinary_answer_origin"):"LEGACY_UNVERIFIED";metadata.put("answerOrigin",origin);metadata.put("projectionBasis",current?flag(row,"managed")?"CURRENT_ORDINARY_NOTE":"LEGACY_UNVERIFIED":"PRESERVED_NATIVE_SNAPSHOT");metadata.put("preview",preview);
        if(flag(row,"managed")){metadata.put("questionVersion",row.get("lock_version"));metadata.put("legacyMark",row.get("legacy_status"));metadata.put("detailLink","/care?patientId="+out.getPatientId()+"&questionId="+out.getId());if("USER_RECORDED".equals(origin)){metadata.put("answerRecorderId",row.get("ordinary_answer_recorder_id"));metadata.put("answerRecordedAt",row.get("ordinary_answer_recorded_at"));}}
        out.setDetails(details);out.setCreatedAt(local(row.get("created_at")));out.setUpdatedAt(local(row.get("updated_at")));out.setEventAt(local(row.get("event_at")));return out;
    }
    private static LocalDateTime local(Object value){return value==null?null:LocalDateTime.parse(value.toString().replace(' ','T'));}
}
