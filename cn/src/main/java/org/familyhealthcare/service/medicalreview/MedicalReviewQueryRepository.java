package org.familyhealthcare.service.medicalreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import java.sql.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewApiContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContentFingerprint.integrity;

/** Fixed bounded queries on the query owner's verified RR resource. Never an authority. */
final class MedicalReviewQueryRepository {
    private static final String EVENTS="SELECT id,record_id,source_generation,channel,operation,before_content_version,content_version,decision_version,cycle,state,actor_id,actor_kind,DATE_FORMAT(occurred_at,'%Y-%m-%dT%H:%i:%s.%f') AS occurred_at FROM medical_review_event ";
    private final Connection connection;private final ConnectionHolder holder;private final Runnable verify;
    MedicalReviewQueryRepository(Connection connection,ConnectionHolder holder,Runnable verify){this.connection=connection;this.holder=holder;this.verify=verify;}
    interface Rows<T>{T read(ResultSet result)throws SQLException;}
    private <T>T query(String sql,Rows<T> work,Object...args){verify.run();try(PreparedStatement s=connection.prepareStatement(sql)){
        int timeout=holder.hasTimeout()?Math.min(5,holder.getTimeToLiveInSeconds()):5;s.setQueryTimeout(timeout);
        for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);
        try(ResultSet r=s.executeQuery()){T value=work.read(r);verify.run();return value;}
    }catch(SQLException bad){throw unavailable();}}
    MedicalRecord binding(long id,Long patient){return query("SELECT id,patient_id,user_id,content_version,verification_status FROM medical_record WHERE id=? LIMIT 2",r->{
        if(!r.next())throw denied();MedicalRecord s=new MedicalRecord();s.setId(positive(r,"id"));s.setPatientId(nullable(r,"patient_id"));s.setUserId(nullable(r,"user_id"));s.setContentVersion(positive(r,"content_version"));s.setVerificationStatus(r.getString("verification_status"));
        if(r.next()||s.getId()!=id)throw integrity();if(!Objects.equals(s.getPatientId(),patient))throw denied();return s;
    },id);}
    Map<String,Object> display(MedicalRecord binding,MedicalReviewContentFingerprint.Content content,MedicalRecordValidityProjection.State state,Map<String,Object> original){
        Map<String,Object> header=query("SELECT id,patient_id,user_id,patient_name,DATE_FORMAT(record_date,'%Y-%m-%d') AS record_date,record_type,hospital_name,dept_name,doctor_name,remark,ai_raw_result,source_type,source_external_id,confidence_score,verification_status,verified_by,DATE_FORMAT(verified_at,'%Y-%m-%dT%H:%i:%s.%f') AS verified_at,verification_note,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') AS created_at,DATE_FORMAT(updated_at,'%Y-%m-%dT%H:%i:%s.%f') AS updated_at,content_version FROM medical_record WHERE id=? LIMIT 2",r->{
            if(!r.next())throw integrity();long id=positive(r,"id"),version=positive(r,"content_version");Long patient=nullable(r,"patient_id"),owner=nullable(r,"user_id");
            if(id!=binding.getId()||version!=binding.getContentVersion()||!Objects.equals(patient,binding.getPatientId())||!Objects.equals(owner,binding.getUserId())||!Objects.equals(r.getString("verification_status"),binding.getVerificationStatus())
                    ||id!=content.recordId()||version!=content.contentVersion()||!Objects.equals(patient,content.patientId())||!Objects.equals(owner,content.userId()))throw integrity();
            String date=r.getString("record_date");MedicalReviewContentFingerprint.date(date);
            Map<String,Object> h=map("id",id,"patientId",patient,"userId",owner,"patientName",r.getString("patient_name"),"recordDate",date,"recordType",r.getString("record_type"),
                    "hospitalName",r.getString("hospital_name"),"deptName",r.getString("dept_name"),"doctorName",r.getString("doctor_name"),"remark",r.getString("remark"),"aiRawResult",r.getString("ai_raw_result"),
                    "sourceType",r.getString("source_type"),"sourceExternalId",r.getString("source_external_id"),"confidenceScore",r.getBigDecimal("confidence_score"),"verificationStatus",r.getString("verification_status"),
                    "verifiedBy",nullable(r,"verified_by"),"verifiedAt",displayTime(r.getString("verified_at")),"verificationNote",r.getString("verification_note"),"createdAt",displayTime(r.getString("created_at")),
                    "updatedAt",displayTime(r.getString("updated_at")),"contentVersion",version,"managed",state.managed,"validity",state.validity,"sourceStatus",state.status());
            if(r.next())throw integrity();return h;
        },binding.getId());
        List<Map<String,Object>> items=query("SELECT id,record_id,item_name,item_code,result_value,unit,reference_range,is_abnormal,ai_confidence,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') AS created_at FROM medical_record_item WHERE record_id=? ORDER BY id LIMIT 101",r->{
            List<Map<String,Object>> rows=new ArrayList<>();long last=0;while(r.next()){
                if(rows.size()==100)throw limit();long id=positive(r,"id"),record=positive(r,"record_id");if(id<=last||record!=binding.getId())throw integrity();last=id;
                rows.add(map("id",id,"recordId",record,"itemName",r.getString("item_name"),"itemCode",r.getString("item_code"),"resultValue",r.getString("result_value"),"unit",r.getString("unit"),"referenceRange",r.getString("reference_range"),"isAbnormal",number(r,"is_abnormal"),"aiConfidence",r.getBigDecimal("ai_confidence"),"createdAt",displayTime(r.getString("created_at"))));
            }return rows;
        },binding.getId());
        List<Map<String,Object>> attachments=query("SELECT id,record_id,file_name,file_type,file_size,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') AS created_at FROM medical_record_attachment WHERE record_id=? ORDER BY id LIMIT 101",r->{
            List<Map<String,Object>> rows=new ArrayList<>();long last=0;while(r.next()){
                if(rows.size()==100)throw limit();long id=positive(r,"id"),record=positive(r,"record_id");if(id<=last||record!=binding.getId())throw integrity();last=id;
                rows.add(map("id",id,"recordId",record,"fileName",r.getString("file_name"),"fileType",r.getString("file_type"),"fileSize",number(r,"file_size"),"createdAt",displayTime(r.getString("created_at"))));
            }return rows;
        },binding.getId());
        // Content canonical is internal and bounded; only child IDs are compared, never returned.
        try {JsonNode canonical=new ObjectMapper().readTree(content.canonicalEnvelope());sameIds(canonical.get("items"),items);sameIds(canonical.get("attachments"),attachments);}
        catch(java.io.IOException bad){throw integrity();}
        Map<String,Object> out=new LinkedHashMap<>(header);out.put("items",items);out.put("attachments",attachments);out.put("originalImport",original);
        @SuppressWarnings("unchecked") Map<String,Object> frozen=(Map<String,Object>)freeze(out);return frozen;
    }
    private static void sameIds(JsonNode nodes,List<Map<String,Object>> rows){if(nodes==null||!nodes.isArray()||nodes.size()!=rows.size())throw integrity();for(int i=0;i<rows.size();i++)if(!nodes.get(i).get("id").isIntegralNumber()||nodes.get(i).get("id").longValue()!=(Long)rows.get(i).get("id"))throw integrity();}
    Event event(long record,String generation,Channel channel,long version){return query(EVENTS+"WHERE record_id=? AND source_generation=? AND channel=? AND decision_version=? LIMIT 2",r->{if(!r.next())return null;Event e=new Event(r);if(r.next())throw integrity();e.scope(record,generation,channel);if(e.decision!=version)throw integrity();return e;},record,generation,channel.name(),version);}
    List<Event> page(long record,String generation,Channel channel,long upper,int size){return query(EVENTS+"WHERE record_id=? AND source_generation=? AND channel=? AND decision_version<=? ORDER BY decision_version DESC LIMIT ?",r->{List<Event> rows=new ArrayList<>();while(r.next()){
        if(rows.size()>size)throw integrity();Event e=new Event(r);e.scope(record,generation,channel);rows.add(e);}return rows;},record,generation,channel.name(),upper,size+1);}
    Map<String,Object> text(Event e){return query("SELECT event_id,record_id,source_generation,text_type,OCTET_LENGTH(text_value) AS text_bytes,CHAR_LENGTH(text_value) AS text_points,SHA2(text_value,256) AS text_hash,CASE WHEN OCTET_LENGTH(text_value)<=4000 AND CHAR_LENGTH(text_value)<=1000 THEN text_value ELSE NULL END AS private_value FROM medical_review_text WHERE event_id=? AND record_id=? AND source_generation=? LIMIT 2",r->{
        String expected=e.operation==Operation.APPROVE?"REVIEW_NOTE":e.operation==Operation.REJECT?"REJECTION_REASON":e.operation==Operation.RESUBMIT?"RESUBMISSION_REASON":null;
        if(!r.next()){if(e.operation==Operation.REJECT||e.operation==Operation.RESUBMIT)throw integrity();return null;}
        if(expected==null||positive(r,"event_id")!=e.id||positive(r,"record_id")!=e.record||!e.generation.equals(r.getString("source_generation"))||!expected.equals(r.getString("text_type")))throw integrity();
        Long bytes=number(r,"text_bytes"),points=number(r,"text_points");String hash=r.getString("text_hash"),value=r.getString("private_value");
        if(bytes==null||points==null||bytes<0||bytes>4000||points<0||points>1000||value==null||r.next())throw integrity();
        try{ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if(encoded.remaining()!=bytes||value.codePointCount(0,value.length())!=points||!WorkflowData.hash(value).equals(hash))throw integrity();}
        catch(CharacterCodingException bad){throw integrity();}
        if(e.operation!=Operation.APPROVE&&value.codePoints().allMatch(MedicalReviewQueryRepository::blank))throw integrity();return map("type",expected,"value",value);
    },e.id,e.record,e.generation);}
    private static boolean blank(int c){return c>=9&&c<=13||c>=28&&c<=32||c==0x85||c==0xa0||c==0x1680||c>=0x2000&&c<=0x200a||c==0x2028||c==0x2029||c==0x202f||c==0x205f||c==0x3000||c==0xfeff;}
    static final class Event {
        final long id,record,content,decision,cycle,actor;final Long before;final String generation,kind,time;final Channel channel;final Operation operation;final State state;
        Event(ResultSet r)throws SQLException{
            id=positive(r,"id");record=positive(r,"record_id");content=positive(r,"content_version");decision=positive(r,"decision_version");cycle=positive(r,"cycle");actor=positive(r,"actor_id");before=nullable(r,"before_content_version");generation=r.getString("source_generation");kind=r.getString("actor_kind");time=r.getString("occurred_at");
            try{channel=Channel.valueOf(r.getString("channel"));operation=Operation.valueOf(r.getString("operation"));state=State.valueOf(r.getString("state"));requireEventFacts(generation,channel,operation,state,before,content,decision,cycle);}
            catch(IllegalArgumentException|NullPointerException|WorkflowException bad){throw integrity();}
            if(!Arrays.asList("ADMIN","ASSIGNED_DOCTOR","OWNER","DELEGATE").contains(kind)||channel==Channel.DOCTOR&&(operation==Operation.APPROVE||operation==Operation.REJECT)&&!Arrays.asList("ADMIN","ASSIGNED_DOCTOR").contains(kind))throw integrity();
            MedicalReviewContentFingerprint.time(time);if(time==null||time.compareTo("1000-01-01T00:00:00.000000")<0)throw integrity();
        }
        void scope(long record,String generation,Channel channel){if(this.record!=record||!this.generation.equals(generation)||this.channel!=channel)throw integrity();}
        void head(MedicalReviewQualificationReader.ChannelFacts f){if(f==null||content!=f.contentVersion||decision!=f.decisionVersion||cycle!=f.cycle||actor!=f.actorId||!state.name().equals(f.state)||!operation.name().equals(f.operation)||!kind.equals(f.actorKind)||!time.equals(f.occurredAt))throw integrity();}
        Map<String,Object> projection(){return map("channel",channel.name(),"operation",operation.name(),"beforeContentVersion",before,"contentVersion",content,"decisionVersion",decision,"cycle",cycle,"state",state.name(),"actorId",actor,"actorKind",kind,"occurredAt",time);}
    }
    /** newer follows older; IDs need only be ordered, never globally contiguous. */
    static void adjacent(Event older,Event newer){
        if(older==null||newer==null||older.decision==MAX_ID||newer.decision!=older.decision+1||newer.id<=older.id||newer.before==null||newer.before!=older.content)throw integrity();
        if(newer.operation==Operation.CONTENT_CHANGED){if(older.content==MAX_ID||newer.content!=older.content+1||newer.cycle!=older.cycle)throw integrity();}
        else if(newer.operation==Operation.RESUBMIT){if(!Arrays.asList(State.APPROVED,State.REJECTED,State.SUPERSEDED).contains(older.state)||older.cycle==MAX_ID||newer.cycle!=older.cycle+1||newer.content!=older.content)throw integrity();}
        else if((newer.operation==Operation.APPROVE||newer.operation==Operation.REJECT)&&older.state==State.PENDING){if(newer.cycle!=older.cycle||newer.content!=older.content)throw integrity();}
        else throw integrity();
    }
    static Long number(ResultSet r,String field)throws SQLException{Object o=r.getObject(field);if(o==null)return null;if(!(o instanceof Number))throw integrity();try{return new BigDecimal(o.toString()).longValueExact();}catch(ArithmeticException|NumberFormatException bad){throw integrity();}}
    static Long nullable(ResultSet r,String field)throws SQLException{Long n=number(r,field);if(n!=null&&(n<1||n>MAX_ID))throw integrity();return n;}
    static long positive(ResultSet r,String field)throws SQLException{Long n=nullable(r,field);if(n==null)throw integrity();return n;}
    private static String displayTime(String value){MedicalReviewContentFingerprint.time(value);return value==null?null:value.substring(0,19).replace('T',' ');}
    static WorkflowException denied(){return new WorkflowException(403,"MEDICAL_RECORD_ACCESS_DENIED","Access denied to source record.");}
    private static WorkflowException limit(){return new WorkflowException(413,"MEDICAL_REVIEW_LIMIT","The medical review request exceeds the supported size limit.");}
}
