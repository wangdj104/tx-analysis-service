package org.familyhealthcare.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** 仅测量写入口使用：保留原 Map 指纹，私有十进制原值只供落库前校验。 */
@JsonDeserialize(using=MeasurementInputBody.Reader.class)
public final class MeasurementInputBody extends AbstractMap<String,Object> {
    private final Map<String,Object> entries;
    private final Map<String,String> decimals;
    private MeasurementInputBody(Map<String,Object> entries,Map<String,String> decimals) {
        this.entries=Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.decimals=Collections.unmodifiableMap(new HashMap<>(decimals));
    }
    @Override public Set<Entry<String,Object>> entrySet() { return entries.entrySet(); }
    @Override public Object get(Object key) { return entries.get(key); }
    public static Object originalValue(Map<String,Object> body,String key) {
        if(body==null)return null;
        if(body instanceof MeasurementInputBody && ((MeasurementInputBody)body).decimals.containsKey(key))return ((MeasurementInputBody)body).decimals.get(key);
        return body.get(key);
    }
    public static BigDecimal decimalValue(Map<String,Object> body,String key,BigDecimal fallback) {
        if(body instanceof MeasurementInputBody && ((MeasurementInputBody)body).decimals.containsKey(key))return new BigDecimal(((MeasurementInputBody)body).decimals.get(key));
        return fallback;
    }
    public static final class Reader extends StdDeserializer<MeasurementInputBody> {
        private static final Set<String> READINGS=new HashSet<>(Arrays.asList("systolicBp","diastolicBp","bloodGlucose","valuePrimary","valueSecondary"));
        public Reader() { super(MeasurementInputBody.class); }
        @Override public MeasurementInputBody deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if(!parser.isExpectedStartObjectToken())return (MeasurementInputBody)context.handleUnexpectedToken(MeasurementInputBody.class,parser);
            Map<String,Object> entries=new LinkedHashMap<>();Map<String,String> decimals=new HashMap<>();
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                String key=parser.currentName();parser.nextToken();
                String raw=READINGS.contains(key)&&parser.currentToken()==JsonToken.VALUE_NUMBER_FLOAT?parser.getText():null;
                // entries 继续使用原 Object 绑定，不改变尾零、指数等旧命令的 JSON 表示。
                entries.put(key,context.readValue(parser,Object.class));
                if(raw==null)decimals.remove(key);else decimals.put(key,raw);
            }
            // 只读请求避免后续字段写入留下陈旧原值；客户端同名字段仍只是普通 entry。
            return new MeasurementInputBody(entries,decimals);
        }
    }
}
