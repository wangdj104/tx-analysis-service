package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.workflow.WorkflowCommandExecutor;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Bounded pure validation; no request field can confer authority. */
public final class AlertWorkflowContracts {
    public static final Set<String> MODES=keys("PERSONAL","CLINICAL");
    public static final Set<String> STATES=keys("OPEN","ACCEPTED","IN_PROGRESS","WAITING_FEEDBACK","READY_FOR_REVIEW","CLOSED","CANCELLED");
    public static final Set<String> SOURCE_TYPES=keys("HEALTH_MEASUREMENT","BP_SELF_MONITOR");
    public static final Set<String> EVIDENCE_TYPES=keys("HEALTH_MEASUREMENT","BP_SELF_MONITOR","MEDICAL_RECORD","CARE_PLAN_ACTION");
    public static final Set<String> METRICS=keys("BP","GLUCOSE","SPO2","WEIGHT","HEART_RATE","TEMPERATURE","CUSTOM");
    private static final Instant MIN=Instant.parse("1000-01-01T00:00:00Z"),MAX=Instant.parse("9999-12-31T23:59:59.499999Z");
    private AlertWorkflowContracts(){}
    /** Allowed payload fields are a server-owned per-command set, never supplied by a client. */
    public static void validateCommand(Map<String,Object> body,Set<String> payloadFields){
        Set<String> allowed=new HashSet<>(payloadFields);allowed.add("commandKey");allowed.add("expectedVersion");fields(body,allowed);
        commandKey(body.get("commandKey"));requireVersion(body.get("expectedVersion"));
    }
    public static String commandKey(Object value){if(!(value instanceof String))throw AlertWorkflowException.invalid("commandKey must be a UUID.");try{return WorkflowCommandExecutor.validateKey((String)value);}catch(WorkflowException e){throw AlertWorkflowException.invalid(e.getMessage());}}
    public static long requireId(Object value,String field){try{return WorkflowData.requireId(value,field);}catch(WorkflowException e){throw AlertWorkflowException.invalid(e.getMessage());}}
    public static long requireVersion(Object value){
        long version;
        try{if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)version=((Number)value).longValue();else if(value instanceof BigInteger)version=((BigInteger)value).longValueExact();else if(value instanceof BigDecimal)version=((BigDecimal)value).longValueExact();else throw AlertWorkflowException.invalid("expectedVersion must be an exact integer.");}
        catch(ArithmeticException e){throw AlertWorkflowException.invalid("expectedVersion must be an exact integer.");}
        if(version<0)throw AlertWorkflowException.invalid("expectedVersion must be non-negative.");return version;
    }
    public static void fields(Map<String,Object> body,Set<String> allowed){if(body==null)throw AlertWorkflowException.invalid("request body is required.");for(Object field:body.keySet())if(!allowed.contains(field))throw AlertWorkflowException.invalid("Unsupported request field.");}
    public static String requireText(Object value,String field,int maximum){
        if(!(value instanceof String))throw AlertWorkflowException.invalid(field+" must be text.");String text=trim((String)value);int size=text.codePointCount(0,text.length());if(size<1||size>maximum)throw AlertWorkflowException.invalid(field+" must contain 1 to "+maximum+" characters.");return text;
    }
    public static String choice(Object value,String field,Set<String> allowed){if(!(value instanceof String)||!allowed.contains(value))throw AlertWorkflowException.invalid(field+" is not supported.");return(String)value;}
    public static Instant parseOffsetInstant(String value){return parseOffsetTime(value).toInstant();}
    private static OffsetDateTime parseOffsetTime(String value){
        try{OffsetDateTime parsed=OffsetDateTime.parse(trim(value),DateTimeFormatter.ISO_OFFSET_DATE_TIME);Instant time=parsed.toInstant();if(time.getNano()%1000!=0||time.isBefore(MIN)||time.isAfter(MAX))throw AlertWorkflowException.invalid("time must fit UTC DATETIME(6) exactly.");return parsed;}
        catch(DateTimeParseException|NullPointerException e){throw AlertWorkflowException.invalid("time must include an ISO-8601 offset.");}
    }
    public static Instant requireOccurredAt(Object value,Instant now){return requireOccurredTime(value,now).toInstant();}
    public static OffsetDateTime requireOccurredTime(Object value,Instant now){if(!(value instanceof String))throw AlertWorkflowException.invalid("occurredAt must include an ISO-8601 offset.");OffsetDateTime occurred=parseOffsetTime((String)value);if(occurred.toInstant().isAfter(now))throw AlertWorkflowException.invalid("occurredAt must not be in the future.");return occurred;}
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> validateEvidence(Object raw){
        if(raw==null)return Collections.emptyList();if(!(raw instanceof List)||((List<?>)raw).size()>5)throw AlertWorkflowException.invalid("evidence must contain at most five references.");
        List<Map<String,Object>> refs=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object item:(List<?>)raw){if(!(item instanceof Map))throw AlertWorkflowException.invalid("evidence reference must be an object.");Map<String,Object> ref=(Map<String,Object>)item;fields(ref,keys("sourceType","sourceId"));String type=choice(ref.get("sourceType"),"sourceType",EVIDENCE_TYPES);long id=requireId(ref.get("sourceId"),"sourceId");if(!seen.add(type+":"+id))throw AlertWorkflowException.invalid("duplicate evidence reference.");refs.add(WorkflowData.map("sourceType",type,"sourceId",id));}return refs;
    }
    /** Immutable snapshots are only bounded source scalar values/context, with no names, prose or URLs. */
    public static void validateSourceSnapshot(Map<String,Object> snapshot){
        fields(snapshot,keys("metricType","valuePrimary","valueSecondary","unit","measuredAt","sourceKind","systolicBp","diastolicBp","bloodGlucose","bgUnit","measurePeriod","recordDate","recordTime","measureType","verificationStatus"));
        if(snapshot.isEmpty())throw AlertWorkflowException.invalid("source snapshot is required.");
        for(Object value:snapshot.values())if(value!=null&&!(value instanceof Number)&&!(value instanceof String))throw AlertWorkflowException.invalid("snapshot values must be scalars.");else if(value instanceof String&&((String)value).codePointCount(0,((String)value).length())>100)throw AlertWorkflowException.invalid("snapshot value is too long.");
    }
    /** Unsupported native metadata edits must be explicit validation failures, never silent loss. */
    public static void requireUnchangedMetadata(Map<String,Object> body,String field,Object current){
        if(body.containsKey(field)&&!Objects.equals(body.get(field),current))throw AlertWorkflowException.invalid(field+" is read-only in versioned corrections; retain the original source context.");
    }
    private static String trim(String value){if(value==null)return null;int start=0,end=value.length();while(start<end){int c=value.codePointAt(start);if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c))break;start+=Character.charCount(c);}while(end>start){int c=value.codePointBefore(end);if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c))break;end-=Character.charCount(c);}return value.substring(start,end);}
    private static Set<String> keys(String...values){return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));}
}
