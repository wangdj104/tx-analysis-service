package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.workflow.WorkflowData;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 仅为当前查询保存原始排序位置，绝不能替代每次当前授权。 */
final class MeasurementTrendCursor {
    private static final String ENDPOINT="measurement-trend";
    private static final int MAX_ENCODED_LENGTH=2048;
    private final Map<String,Object> context;
    MeasurementTrendCursor(long actor,long patient,String source,String metric,String from,String to){
        context=WorkflowData.map("endpoint",ENDPOINT,"protocol",1L,"actor",actor,"patient",patient,"source",source,"metric",metric,"from",from,"to",to);
    }
    Map<String,Object> decode(String encoded){
        if(encoded==null)return null;
        try{
            if(encoded.isEmpty()||encoded.length()>MAX_ENCODED_LENGTH||!encoded.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            Map<String,Object> value=WorkflowData.object(new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8));
            Set<String> keys=new HashSet<>(context.keySet());keys.addAll(Arrays.asList("date","time","id"));
            if(!value.keySet().equals(keys))throw new IllegalArgumentException();
            for(Map.Entry<String,Object> entry:context.entrySet())if(!entry.getValue().equals(value.get(entry.getKey())))throw new IllegalArgumentException();
            if(!(value.get("id") instanceof Long)||(Long)value.get("id")<=0)throw new IllegalArgumentException();
            if(value.get("date")!=null&&!(value.get("date") instanceof String)||value.get("time")!=null&&!(value.get("time") instanceof String))throw new IllegalArgumentException();
            return value;
        }catch(RuntimeException failure){throw AlertWorkflowException.invalid("Invalid cursor for this measurement query.");}
    }
    String encode(Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>(context);result.put("date",row.get("recordDate"));result.put("time",row.get("sortTime"));result.put("id",row.get("sourceId"));
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(WorkflowData.json(result).getBytes(StandardCharsets.UTF_8));
        // 输出与输入使用同一总预算；不能发出本接口自己无法续读的游标，也不能截断原键。
        if(encoded.length()>MAX_ENCODED_LENGTH)throw MeasurementReadReadiness.unavailable();
        return encoded;
    }
}
