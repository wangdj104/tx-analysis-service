package org.familyhealthcare.service.archivestock;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** This envelope controls one restored stock identity; it never imports archive authority. */
public final class ArchiveStockNotificationContracts {
    public static final long MAX = 9007199254740991L;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_LONG_FOR_INTS);
    private ArchiveStockNotificationContracts() { }
    public enum Operation { ENABLE, DISABLE }
    public static final class Command {
        public final long patientId, stockId, expectedControlVersion;
        public final String commandKey, currentFactsHash, payloadHash;
        public final Operation operation;
        private Command(Map<String,Object> value) {
            patientId=id(value.get("patientId")); stockId=id(value.get("stockId"));
            expectedControlVersion=version(value.get("expectedControlVersion"));
            commandKey=key(value.get("commandKey"));
            try { operation=Operation.valueOf((String)value.get("operation")); } catch (RuntimeException failure) { throw invalid(); }
            Set<String> fields=new HashSet<>(Arrays.asList("patientId","stockId","operation","expectedControlVersion","commandKey"));
            if(operation==Operation.ENABLE) {
                fields.add("currentFactsHash"); fields.add("reviewAcknowledged");
                if(!Boolean.TRUE.equals(value.get("reviewAcknowledged")))throw invalid();
                currentFactsHash=digest(value.get("currentFactsHash"));
            } else currentFactsHash=null;
            if(!fields.equals(value.keySet()))throw invalid();
            payloadHash=WorkflowData.hash(WorkflowData.json(value));
        }
    }
    public static Command command(String raw) { return new Command(parse(raw,2048)); }
    public static long id(Object value) { long n=version(value); if(n==0)throw invalid(); return n; }
    public static long version(Object value) {
        if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long))throw invalid();
        long n=((Number)value).longValue();if(n<0||n>MAX)throw invalid();return n;
    }
    public static long next(long version) { if(version<0||version>=MAX)throw conflict("VERSION_EXHAUSTED");return version+1; }
    public static String key(Object value) {
        if(!(value instanceof String)||!((String)value).matches("[A-Za-z0-9][A-Za-z0-9_-]{15,63}"))throw invalid();return (String)value;
    }
    public static String digest(Object value) { if(!(value instanceof String)||!((String)value).matches("[0-9a-f]{64}"))throw invalid();return (String)value; }
    public static String timestamp(Instant value) { return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value); }
    public static Map<String,Object> receipt(long stock,Operation operation,long version,Instant now) {
        return storedReceipt(WorkflowData.json(WorkflowData.map("stockId",id(stock),"operation",operation.name(),
                "controlVersion",id(version),"enabled",operation==Operation.ENABLE,"recordedAt",timestamp(now))));
    }
    public static Map<String,Object> storedReceipt(String raw) {
        Map<String,Object> r;try{r=parse(raw,512);}catch(RuntimeException failure){throw integrity();}
        if(!r.keySet().equals(new HashSet<>(Arrays.asList("stockId","operation","controlVersion","enabled","recordedAt"))))throw integrity();
        id(r.get("stockId"));id(r.get("controlVersion"));
        if(!(r.get("enabled") instanceof Boolean)||!Arrays.asList("ENABLE","DISABLE").contains(r.get("operation"))
                ||!Objects.equals(r.get("enabled"),"ENABLE".equals(r.get("operation"))))throw integrity();
        Object time=r.get("recordedAt");
        if(!(time instanceof String)||!((String)time).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z"))throw integrity();
        try { if(!timestamp(Instant.parse((String)time)).equals(time))throw integrity(); }catch(RuntimeException failure){throw integrity();}
        return Collections.unmodifiableMap(r);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> parse(String raw,int max) {
        if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>max)throw invalid();
        try {
            try(JsonParser parser=JSON.getFactory().createParser(raw)){
                com.fasterxml.jackson.core.JsonToken token;
                while((token=parser.nextToken())!=null)if(token.isNumeric()&&(token!=com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT||!parser.getText().matches("0|[1-9][0-9]*")))throw invalid();
            }
            Object value=JSON.readValue(raw,Object.class);if(!(value instanceof Map))throw invalid();return (Map<String,Object>)value;
        }
        catch(java.io.IOException failure){throw invalid();}
    }
    public static WorkflowException invalid(){return new WorkflowException(400,"ARCHIVE_STOCK_INVALID","Invalid restored-stock reminder review.");}
    public static WorkflowException conflict(String code){return new WorkflowException(409,"ARCHIVE_STOCK_"+code,"Read the current stock reminder settings and review again.");}
    public static WorkflowException integrity(){return new WorkflowException(503,"ARCHIVE_STOCK_INTEGRITY","Restored-stock reminder state could not be verified.");}
    public static WorkflowException unknown(){return new WorkflowException(409,"ARCHIVE_STOCK_RECEIPT_UNKNOWN","The original action is not confirmed. Keep its original key and check again.");}
}
