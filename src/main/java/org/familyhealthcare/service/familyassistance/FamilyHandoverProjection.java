package org.familyhealthcare.service.familyassistance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Instant;
import org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService.HandoverPermission;

/** 当前普通来源与责任事实的有限投影；不制造历史快照、接受事实或权限票据。 */
public final class FamilyHandoverProjection {
    private static final int JSON_BYTES=64*1024,EVIDENCE_BYTES=16*1024,MAX_DEPTH=16,MAX_NODES=4096;
    private static final JsonFactory JSON=new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> STATES=new HashSet<>(Arrays.asList("REQUESTED","ACCEPTED","AWAITING_CONFIRMATION","COMPLETED","CANCELLED","CLOSED_UNRESOLVED"));
    private static final List<String> REVISION_FIELDS=Arrays.asList("id","patient_id","source_kind","care_item_id","care_appointment_id",
            "request_kind","lock_version","cycle","state","originator_id","assigned_user_id","accepted_by","handled_by","accepted_at","handled_at","confirmed_at","deadline_at","origin_evidence_hash","evidence_bytes");
    private FamilyHandoverProjection() { }

    static Map<String,Object> project(Map<String,Object> row,long patient,Long requested,boolean enabled) {
        return project(row,patient,requested,enabled,true);
    }
    static Map<String,Object> context(Map<String,Object> row,long patient,Long requested,boolean enabled) {
        return project(row,patient,requested,enabled,false);
    }
    private static Map<String,Object> project(Map<String,Object> row,long patient,Long requested,boolean enabled,boolean includeContent) {
        requireSource(row,patient);
        String selected=requested==null?"c":"a";
        if(requested!=null)requireIdentity(row,"a",patient,requested);
        Long pointer=number(row.get("s_pointer"));
        boolean untracked=Objects.equals(number(row.get("s_marker")),0L)&&pointer==null;
        boolean tracked=Objects.equals(number(row.get("s_marker")),1L)&&positive(row.get("s_pointer"));
        boolean valid=untracked&&requested==null;
        if(tracked) {
            requireIdentity(row,"c",patient,pointer);
            valid=validEvidence(row,"c")&&validRevision(row,"c");
            if(requested!=null)valid&=validEvidence(row,"a")&&validRevision(row,"a");
        }
        boolean current=valid&&tracked&&(requested==null||Objects.equals(pointer,requested));
        String status=!valid?"UNAVAILABLE":requested!=null&&!current?"NOT_CURRENT":"AVAILABLE";
        Content body="AVAILABLE".equals(status)?(includeContent?content(row):new Content(true,null,null)):Content.unavailable();
        if("AVAILABLE".equals(status)&&!body.available)status="UNAVAILABLE";
        // 未通过当前身份与完整性校验时，不给出可点击的后继请求编号。
        Long aid=requested!=null?requested:valid&&tracked?pointer:null;
        boolean revision=aid!=null&&(requested!=null||valid&&tracked);
        Map<String,Object> out=WorkflowData.map("patientId",patient,"sourceKind","CARE_ITEM","careItemId",number(row.get("s_id")),
                "requestKind","HANDOVER","familyWorkflowVersion",number(row.get("s_marker")),
                "assistanceId",aid,"version",revision?number(row.get(selected+"_lock_version")):null,
                "cycle",revision?number(row.get(selected+"_cycle")):null,"state",revision?row.get(selected+"_state"):null,
                "currentAssistanceId",valid?pointer:null,"currentSource",current,
                "contentStatus",status,"basis","CURRENT_NATIVE_HANDOVER_CONTENT",
                "title","AVAILABLE".equals(status)?body.title:null,"note","AVAILABLE".equals(status)?body.note:null,
                "sourceReadiness",!valid||"UNAVAILABLE".equals(status)?"UNAVAILABLE":!enabled?"FEATURE_DISABLED":untracked?"UNTRACKED":terminal(row.get("c_state"))?"CURRENT_TERMINAL":"CURRENT_ACTIVE");
        // 包括 JSON 转义开销；即使以后 DTO 增字段，也不能无界返回正文。
        if(WorkflowData.json(out).getBytes(StandardCharsets.UTF_8).length>96*1024)throw unavailable();
        return new SafeContentMap(out);
    }

    static void requireSame(Map<String,Object> first,Map<String,Object> last,boolean requested) {
        if(first==null||last==null)throw changed();
        for(String key:Arrays.asList("s_id","s_patient_id","s_kind","s_marker","s_pointer","owner_id"))if(!equal(first.get(key),last.get(key)))throw changed();
        for(String prefix:requested?Arrays.asList("a","c"):Collections.singletonList("c"))
            for(String field:REVISION_FIELDS)if(!equal(first.get(prefix+"_"+field),last.get(prefix+"_"+field)))throw changed();
    }

    static Set<Long> participants(Map<String,Object> row,String prefix) {
        Set<Long> ids=new TreeSet<>();
        for(String field:Arrays.asList("originator_id","assigned_user_id","accepted_by")) {
            Object value=row.get(prefix+"_"+field);
            if(value!=null){if(!positive(value))throw unavailable();ids.add(number(value));}
        }
        // 已处理人的编号也必须精确，但不把第四种身份扩进姓名目录。
        Object handled=row.get(prefix+"_handled_by");if(handled!=null&&!positive(handled))throw unavailable();
        return ids;
    }
    static Map<String,Object> enrich(Map<String,Object> out,Map<String,Object> row,String prefix,
                                     Map<Long,HandoverPermission> permissions,Map<Long,String> names,Instant now) {
        Long origin=number(row.get(prefix+"_originator_id")),assigned=number(row.get(prefix+"_assigned_user_id")),accepted=number(row.get(prefix+"_accepted_by"));
        boolean assignedCurrent=canWrite(permissions,assigned),handlerCurrent=canWrite(permissions,accepted);
        out.put("originatorId",origin);out.put("assignedUserId",assigned);out.put("acceptedBy",accepted);
        out.put("handledBy",number(row.get(prefix+"_handled_by")));
        out.put("acceptedAt",FamilyAssistanceRepository.iso(row.get(prefix+"_accepted_at")));
        out.put("handledAt",FamilyAssistanceRepository.iso(row.get(prefix+"_handled_at")));
        out.put("confirmedAt",FamilyAssistanceRepository.iso(row.get(prefix+"_confirmed_at")));
        String deadline=FamilyAssistanceRepository.iso(row.get(prefix+"_deadline_at"));out.put("deadlineAt",deadline);
        try {out.put("overdue",!terminal(out.get("state"))&&deadline!=null&&!Instant.parse(deadline).isAfter(now));}
        catch(java.time.DateTimeException failure){throw unavailable();}
        out.put("originator",participant(origin,permissions,names));out.put("assigned",participant(assigned,permissions,names));
        out.put("handler",participant(accepted,permissions,names));out.put("assignedCurrent",assignedCurrent);out.put("handlerCurrent",handlerCurrent);
        // 失权不能抹去曾经接受或处理的事实；具名待接受与已接手必须分开。
        out.put("responsibilityStatus",responsibility(Boolean.TRUE.equals(out.get("currentSource")),out.get("state"),
                assigned,accepted,assignedCurrent,handlerCurrent));return out;
    }
    private static String responsibility(boolean current,Object state,Long assigned,Long accepted,boolean assignedCurrent,boolean handlerCurrent) {
        if(!current)return "HISTORIC";
        if(terminal(state))return "ENDED";
        if(assigned!=null&&!assignedCurrent||accepted!=null&&!handlerCurrent)return "ACCESS_LOST";
        if(assigned==null)return "UNASSIGNED";
        if(accepted==null)return "AWAITING_ACCEPTANCE";
        return "AWAITING_CONFIRMATION".equals(state)?"AWAITING_CONFIRMATION":"ACCEPTED";
    }
    static boolean canWrite(Map<Long,HandoverPermission> permissions,Long id) {
        return id!=null&&permissions.containsKey(id)&&permissions.get(id).canWrite();
    }
    static Map<String,Object> participant(Long id,Map<Long,HandoverPermission> permissions,Map<Long,String> names) {
        return id==null?null:WorkflowData.map("userId",id,"displayName",canWrite(permissions,id)?names.get(id):null);
    }
    static Map<String,Object> bounded(Map<String,Object> out,int maximum) {
        if(WorkflowData.json(out).getBytes(StandardCharsets.UTF_8).length>maximum)throw unavailable();
        return new SafeContentMap(out);
    }
    static String search(String value) {
        if(value==null)return null;
        if(!text(value,50,200))throw invalidRequest();
        String trimmed=value.trim();return trimmed.isEmpty()?null:trimmed;
    }

    private static void requireSource(Map<String,Object> row,long patient) {
        if(row==null||!Objects.equals(number(row.get("s_patient_id")),patient)||!positive(row.get("s_id"))
                ||!"HANDOVER".equals(row.get("s_kind")))throw accessDenied();
    }
    private static void requireIdentity(Map<String,Object> row,String prefix,long patient,long assistance) {
        if(!Objects.equals(number(row.get(prefix+"_id")),assistance)||!Objects.equals(number(row.get(prefix+"_patient_id")),patient)
                ||!"CARE_ITEM".equals(row.get(prefix+"_source_kind"))||!Objects.equals(number(row.get(prefix+"_care_item_id")),number(row.get("s_id")))
                ||row.get(prefix+"_care_appointment_id")!=null||!"HANDOVER".equals(row.get(prefix+"_request_kind")))
            throw accessDenied();
    }
    private static boolean validRevision(Map<String,Object> row,String prefix) {
        return positive(row.get(prefix+"_lock_version"))&&positive(row.get(prefix+"_cycle"))&&positive(row.get(prefix+"_originator_id"))
                &&STATES.contains(row.get(prefix+"_state"));
    }
    private static boolean validEvidence(Map<String,Object> row,String prefix) {
        String actual="c".equals(prefix)&&Objects.equals(number(row.get("c_id")),number(row.get("a_id")))?"a":prefix;
        Object raw=row.get(actual+"_evidence_json"),length=row.get(actual+"_evidence_bytes"),hash=row.get(prefix+"_origin_evidence_hash");
        return raw instanceof String&&length instanceof Number&&((Number)length).longValue()<=EVIDENCE_BYTES
                &&((String)raw).getBytes(StandardCharsets.UTF_8).length<=EVIDENCE_BYTES
                &&hash instanceof String&&WorkflowData.hash((String)raw).equals(hash);
    }
    private static Content content(Map<String,Object> row) {
        try {
            Object title=row.get("s_title"),raw=row.get("s_data_json");
            if(!within(row.get("s_title_bytes"),800)||!text(title,200,800)||!within(row.get("s_data_bytes"),JSON_BYTES))return Content.unavailable();
            // SQL NULL 表示未提供说明；有长度但 CASE 没返回正文则是不可读，不能冒充空说明。
            if(title==null&&row.get("s_title_bytes")!=null||raw==null&&row.get("s_data_bytes")!=null)return Content.unavailable();
            Map<?,?> details=raw==null?Collections.emptyMap():strictObject((String)raw);
            if(FamilyAssistanceQueryService.restricted(details))return Content.unavailable();
            Object note=details.get("note");if(!text(note,4000,16*1024))return Content.unavailable();
            return new Content(true,(String)title,(String)note);
        } catch(IOException|IllegalArgumentException failure) {
            // 解析异常可能含正文和键名，不能写日志，也不能作为异常原因传给上层。
            return Content.unavailable();
        }
    }
    private static Map<?,?> strictObject(String raw)throws IOException {
        if(raw.getBytes(StandardCharsets.UTF_8).length>JSON_BYTES)throw new IllegalArgumentException();
        try(JsonParser parser=JSON.createParser(raw)) {
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw new IllegalArgumentException();
            Object result=read(parser,0,new int[]{0});
            if(parser.nextToken()!=null)throw new IllegalArgumentException();
            return (Map<?,?>)result;
        }
    }
    private static Object read(JsonParser parser,int depth,int[] nodes)throws IOException {
        if(++nodes[0]>MAX_NODES)throw new IllegalArgumentException();
        JsonToken token=parser.currentToken();
        if(token==JsonToken.START_OBJECT||token==JsonToken.START_ARRAY) {
            if(++depth>MAX_DEPTH)throw new IllegalArgumentException();
            if(token==JsonToken.START_OBJECT) {
                Map<String,Object> values=new LinkedHashMap<>();
                while(parser.nextToken()!=JsonToken.END_OBJECT) {
                    if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new IllegalArgumentException();
                    String key=parser.currentName();if(parser.nextToken()==null)throw new IllegalArgumentException();
                    values.put(key,read(parser,depth,nodes));
                }
                return values;
            }
            List<Object> values=new ArrayList<>();
            while(parser.nextToken()!=JsonToken.END_ARRAY)values.add(read(parser,depth,nodes));
            return values;
        }
        if(token==JsonToken.VALUE_STRING)return parser.getText();
        if(token==JsonToken.VALUE_NULL)return null;
        if(token==JsonToken.VALUE_TRUE||token==JsonToken.VALUE_FALSE)return token==JsonToken.VALUE_TRUE;
        // 未知数值字段仅用于完整结构检查，不解析为巨型数字，也绝不放入返回 DTO。
        if(token==JsonToken.VALUE_NUMBER_INT||token==JsonToken.VALUE_NUMBER_FLOAT)return 0L;
        throw new IllegalArgumentException();
    }
    static boolean text(Object value,int points,int bytes) {
        if(value==null)return true;if(!(value instanceof String))return false;String s=(String)value;
        if(s.codePointCount(0,s.length())>points||s.getBytes(StandardCharsets.UTF_8).length>bytes)return false;
        for(int i=0;i<s.length();i++)if(Character.isHighSurrogate(s.charAt(i))){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))return false;}
            else if(Character.isLowSurrogate(s.charAt(i)))return false;
        return true;
    }
    private static boolean within(Object value,int bytes){return value==null||value instanceof Number&&((Number)value).longValue()>=0&&((Number)value).longValue()<=bytes;}
    private static boolean positive(Object value){Long n=number(value);return n!=null&&n>0&&n<=9007199254740991L;}
    private static Long number(Object value){return value instanceof Number?((Number)value).longValue():null;}
    private static boolean equal(Object a,Object b){return a instanceof Number&&b instanceof Number?Objects.equals(number(a),number(b)):Objects.equals(a,b);}
    private static boolean terminal(Object state){return Arrays.asList("COMPLETED","CANCELLED","CLOSED_UNRESOLVED").contains(state);}
    public static WorkflowException invalidRequest(){return WorkflowException.invalid("Invalid handover read request.");}
    public static WorkflowException accessDenied(){return new WorkflowException(403,"ACCESS_DENIED","Current source access is required.");}
    static WorkflowException storageNotReady(){return new WorkflowException(503,"SCHEMA_NOT_READY","Household workflow storage is not ready.");}
    static WorkflowException changed(){return new WorkflowException(409,"HANDOVER_PROJECTION_CHANGED","The handover changed. Refresh and review its current details.");}
    static WorkflowException candidatesOverflow(){return new WorkflowException(409,"HANDOVER_CANDIDATES_OVERFLOW","Too many related participants. Narrow the name search before choosing an account.");}
    static WorkflowException invalidCursor(){return new WorkflowException(400,"HANDOVER_INVALID_CURSOR","This page cursor no longer matches the current handover view. Start from the first page.");}
    static WorkflowException featureDisabled(){return new WorkflowException(503,"FEATURE_DISABLED","Family assistance is disabled.");}
    static WorkflowException actionConflict(String code){return new WorkflowException(409,code,"The handover is not available for this operation. Refresh and review its current details.");}
    static WorkflowException eligibilityLimit(){return new WorkflowException(503,"HANDOVER_ELIGIBILITY_LIMIT","Current participant permissions exceed the supported verification limit. No eligibility result is available.");}
    static WorkflowException unavailable(){return new WorkflowException(503,"HANDOVER_PROJECTION_UNAVAILABLE","Current handover details are unavailable. Refresh and try again.");}
    /** 框架诊断常调用 Result.toString；正文只参与 JSON 响应，不能进入诊断字符串。 */
    private static final class SafeContentMap extends LinkedHashMap<String,Object> {
        private static final long serialVersionUID=1L;
        SafeContentMap(Map<String,Object> values){super(values);}
        @Override public String toString(){return "[current handover projection]";}
    }
    private static final class Content {
        final boolean available;final String title,note;
        Content(boolean available,String title,String note){this.available=available;this.title=title;this.note=note;}
        static Content unavailable(){return new Content(false,null,null);}
    }
}
