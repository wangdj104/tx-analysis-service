package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.dto.MedicalRecordReviewSummary;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;

/** Explicit immutable HTTP projections and bounded selectors. None confers authority. */
public final class MedicalReviewApiContracts {
    private MedicalReviewApiContracts() { }
    public static long id(String value) {
        if(value==null||value.length()>16||!value.matches("[1-9][0-9]*"))throw invalidRequest();
        try { long n=Long.parseLong(value);if(n>MAX_ID)throw invalidRequest();return n; }
        catch(NumberFormatException invalid){throw invalidRequest();}
    }
    public static Long nullableId(String value){return "null".equals(value)?null:id(value);}
    public static int size(String value){if(value==null)return 20;long n=id(value);if(n>50)throw invalidRequest();return (int)n;}
    public static Channel channel(String value){if("source".equals(value))return Channel.SOURCE;if("doctor".equals(value))return Channel.DOCTOR;throw invalidRequest();}
    static void reviewChannel(Channel value){if(value!=Channel.SOURCE&&value!=Channel.DOCTOR)throw invalidRequest();}
    static long safeId(long value){if(value<1||value>MAX_ID)throw invalidRequest();return value;}
    public static WorkflowException invalidRequest(){return error(400,"MEDICAL_REVIEW_INVALID_REQUEST");}
    public static WorkflowException unavailable(){return error(503,"MEDICAL_REVIEW_UNAVAILABLE");}
    public static WorkflowException error(int status,String code){
        String message;
        boolean chinese=true;
        switch(code){
            case "MEDICAL_REVIEW_INVALID_REQUEST":message="The medical review request is invalid.";break;
            case "MEDICAL_REVIEW_ROUTE_NOT_FOUND":message="The medical review route is unavailable.";break;
            case "MEDICAL_REVIEW_EVENT_UNAVAILABLE":message="The medical review event is unavailable.";break;
            case "MEDICAL_REVIEW_METHOD_NOT_ALLOWED":message="The request method is not allowed.";break;
            case "MEDICAL_REVIEW_UNSUPPORTED_MEDIA_TYPE":message="The request media type is not supported.";break;
            case "MEDICAL_REVIEW_HISTORY_CHANGED":message="The medical review history has changed.";break;
            case "AUTHENTICATION_REQUIRED":message="Authentication is required.";break;
            case "CURRENT_RESULT_UNAVAILABLE":message="Current medical record review metadata is unavailable.";break;
            case "MEDICAL_REVIEW_LIMIT":message="The medical review request exceeds the supported size limit.";break;
            default:message="The medical review service is unavailable.";
        }
        if(chinese)switch(code){
            case "MEDICAL_REVIEW_INVALID_REQUEST":message="医疗审核请求无效。";break;
            case "MEDICAL_REVIEW_ROUTE_NOT_FOUND":message="医疗审核路径不可用。";break;
            case "MEDICAL_REVIEW_EVENT_UNAVAILABLE":message="医疗审核事件不可用。";break;
            case "MEDICAL_REVIEW_METHOD_NOT_ALLOWED":message="不允许使用此请求方法。";break;
            case "MEDICAL_REVIEW_UNSUPPORTED_MEDIA_TYPE":message="不支持此请求媒体类型。";break;
            case "MEDICAL_REVIEW_HISTORY_CHANGED":message="医疗审核历史已发生变化。";break;
            case "AUTHENTICATION_REQUIRED":message="请先登录。";break;
            case "CURRENT_RESULT_UNAVAILABLE":message="暂时无法获取当前医疗记录审核信息。";break;
            case "MEDICAL_REVIEW_LIMIT":message="医疗审核请求超出支持的大小限制。";break;
            default:message="医疗审核服务暂时不可用。";
        }
        return new WorkflowException(status,code,message);
    }
    /** Only explicit JSON values are admitted; an internal bean can never enter a result. */
    public static Map<String,Object> map(Object... pairs){
        if(pairs==null||(pairs.length&1)!=0)throw unavailable();
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2){if(!(pairs[i] instanceof String)||out.containsKey(pairs[i]))throw unavailable();out.put((String)pairs[i],freeze(pairs[i+1]));}
        return new PrivateMap(out);
    }
    static Object freeze(Object value){
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Byte||value instanceof Short
                ||value instanceof Integer||value instanceof Long||value instanceof BigDecimal||value instanceof BigInteger)return value;
        if(value instanceof Map){Map<String,Object> out=new LinkedHashMap<>();for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){
            if(!(entry.getKey() instanceof String))throw unavailable();out.put((String)entry.getKey(),freeze(entry.getValue()));}return new PrivateMap(out);}
        if(value instanceof List){List<Object> out=new ArrayList<>();for(Object item:(List<?>)value)out.add(freeze(item));return Collections.unmodifiableList(out);}
        throw unavailable();
    }
    /** Framework response diagnostics must not stringify source text, notes or preparation tokens. */
    private static final class PrivateMap extends AbstractMap<String,Object> {
        private final Map<String,Object> values;
        PrivateMap(Map<String,Object> values){this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));}
        @Override public Set<Map.Entry<String,Object>> entrySet(){return values.entrySet();}
        @Override public Object get(Object key){return values.get(key);}
        @Override public boolean containsKey(Object key){return values.containsKey(key);}
        @Override public String toString(){return "[private medical review payload]";}
    }
    static Map<String,Object> commandResult(MedicalReviewReceipt value){return map("contractVersion",1,"receipt",receipt(value));}
    static Map<String,Object> reconciliationResult(MedicalReviewReceipt value){return map("contractVersion",1,"status",value==null?"UNKNOWN":"FOUND","receipt",value==null?null:receipt(value));}
    private static Map<String,Object> receipt(MedicalReviewReceipt r){if(r==null)throw unavailable();return map("recordId",r.recordId,"channel",r.channel.name(),"operation",r.operation.name(),
            "contentVersion",r.contentVersion,"decisionVersion",r.decisionVersion,"cycle",r.cycle,"state",r.state.name(),"actorId",r.actorId,"actorKind",r.actorKind,"appliedAt",r.appliedAt);}
    static Map<String,Object> summary(MedicalRecordReviewSummary s){if(s==null)throw unavailable();return map("availability",s.getAvailability(),"participation",s.getParticipation(),
            "automaticInputEligible",s.getAutomaticInputEligible(),"sourceStatus",s.getSourceStatus(),"source",summaryChannel(s.getSource()),"doctor",summaryChannel(s.getDoctor()),
            "qualificationSemantics",s.getQualificationSemantics(),"checkedAt",s.getCheckedAt());}
    private static Map<String,Object> summaryChannel(MedicalRecordReviewSummary.Channel c){return c==null?null:map("state",c.getState(),"contentVersion",c.getContentVersion(),
            "decisionVersion",c.getDecisionVersion(),"cycle",c.getCycle(),"operation",c.getOperation(),"actorId",c.getActorId(),"actorKind",c.getActorKind(),"occurredAt",c.getOccurredAt());}

    static final class Cursor {
        final long actor,record,watermark,highWater,event,upper;final Long patient,owner;final Channel channel;final String generation;final int size;
        Cursor(long actor,long record,Long patient,Long owner,Channel channel,String generation,long watermark,long highWater,long event,long upper,int size){
            this.actor=safeId(actor);this.record=safeId(record);this.patient=patient;this.owner=owner;if(patient!=null)safeId(patient);if(owner!=null)safeId(owner);
            reviewChannel(channel);this.channel=channel;if(generation==null||!generation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalidRequest();this.generation=generation;
            if(watermark<0||watermark>MAX_ID||size<1||size>50)throw invalidRequest();this.watermark=watermark;this.highWater=safeId(highWater);this.event=safeId(event);this.upper=safeId(upper);if(upper>highWater)throw invalidRequest();this.size=size;
        }
        String encode(){String s="1:"+actor+":"+record+":"+patient+":"+owner+":"+channel.name()+":"+generation+":"+watermark+":"+highWater+":"+event+":"+upper+":"+size;
            byte[] b=s.getBytes(StandardCharsets.US_ASCII);String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(b);if(b.length>240||encoded.length()>320)throw invalidRequest();return encoded;}
        Cursor withUpper(long next){return new Cursor(actor,record,patient,owner,channel,generation,watermark,highWater,event,next,size);}
    }
    public static Cursor cursor(String value){
        if(value==null||value.isEmpty()||value.length()>320||!value.matches("[A-Za-z0-9_-]+"))throw invalidRequest();
        try{
            byte[] b=Base64.getUrlDecoder().decode(value);if(b.length>240)throw invalidRequest();for(byte v:b)if(v<0)throw invalidRequest();
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(b).equals(value))throw invalidRequest();String[] f=new String(b,StandardCharsets.US_ASCII).split(":",-1);
            if(f.length!=12||!"1".equals(f[0]))throw invalidRequest();Channel c="SOURCE".equals(f[5])?Channel.SOURCE:"DOCTOR".equals(f[5])?Channel.DOCTOR:null;
            return new Cursor(id(f[1]),id(f[2]),nullableId(f[3]),nullableId(f[4]),c,f[6],"0".equals(f[7])?0:id(f[7]),id(f[8]),id(f[9]),id(f[10]),size(f[11]));
        }catch(IllegalArgumentException bad){throw invalidRequest();}
    }
}
