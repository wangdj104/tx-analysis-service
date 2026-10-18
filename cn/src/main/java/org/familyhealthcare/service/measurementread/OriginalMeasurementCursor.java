package org.familyhealthcare.service.measurementread;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.familyhealthcare.service.workflow.WorkflowData;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

/** 原键只保存分页位置；不得把格式化时间或默认时刻写入游标。 */
final class OriginalMeasurementCursor {
    static final String SELF_ORDER="record_date DESC,record_time DESC,id DESC";
    static final String JOURNEY_ORDER="measured_at DESC,id DESC";
    private static final int MAX_LENGTH=2048;
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String,Object> context;
    private final boolean self;private final LocalDate from,to;
    OriginalMeasurementCursor(long actor,long patient,boolean self,String filter,LocalDate from,LocalDate to){
        this.self=self;this.from=from;this.to=to;
        context=WorkflowData.map("endpoint",self?"/bp-self-monitor/page":"/care-journey/measurements/page","protocol",1L,"actor",actor,"patient",patient,
                "source",self?"BP_SELF_MONITOR":"HEALTH_MEASUREMENT","filter",filter,"from",from==null?null:from.toString(),"to",to==null?null:to.toString(),"order",self?SELF_ORDER:JOURNEY_ORDER);
    }
    Map<String,Object> decode(String encoded){
        if(encoded==null)return null;
        try{
            if(encoded.isEmpty()||encoded.length()>MAX_LENGTH||!encoded.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded))).toString();
            Map<String,Object> value=JSON.readValue(text,new TypeReference<Map<String,Object>>(){});
            validate(value);return value;
        }catch(Exception failure){throw OriginalMeasurementReadAuthorization.invalid();}
    }
    String encode(Map<String,Object> row){
        try{
            Map<String,Object> value=new LinkedHashMap<>(context);value.put("date",self?row.get("recordDate"):null);value.put("time",row.get(self?"recordTime":"measured_at"));value.put("id",row.get("id"));validate(value);
            String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(WorkflowData.json(value).getBytes(StandardCharsets.UTF_8));
            if(encoded.length()>MAX_LENGTH)throw new IllegalArgumentException();return encoded;
        }catch(RuntimeException failure){throw OriginalMeasurementReadAuthorization.unavailable();}
    }
    private void validate(Map<String,Object> value){
        if(value==null)throw new IllegalArgumentException();Set<String>keys=new HashSet<>(context.keySet());keys.addAll(Arrays.asList("date","time","id"));
        if(!keys.equals(value.keySet()))throw new IllegalArgumentException();for(Map.Entry<String,Object>e:context.entrySet())if(!Objects.equals(e.getValue(),value.get(e.getKey())))throw new IllegalArgumentException();
        if(!(value.get("id")instanceof Long)||(Long)value.get("id")<=0)throw new IllegalArgumentException();
        Object date=value.get("date"),time=value.get("time");if(date!=null&&!(date instanceof String)||time!=null&&!(time instanceof String))throw new IllegalArgumentException();
        LocalDate day=null;if(self){if(date!=null)day=OriginalMeasurementReadService.date((String)date);}
        else{if(date!=null)throw new IllegalArgumentException();if(time!=null){String raw=(String)time;if(raw.length()>32)throw new IllegalArgumentException();LocalDateTime at=LocalDateTime.parse(raw.replace(' ','T'));if(at.getYear()<1000||at.getYear()>9999)throw new IllegalArgumentException();day=at.toLocalDate();}}
        if(from!=null&&(day==null||day.isBefore(from)||day.isAfter(to)))throw new IllegalArgumentException();
    }
}
