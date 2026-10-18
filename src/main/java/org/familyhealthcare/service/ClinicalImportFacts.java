package org.familyhealthcare.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.util.MeasurementInputNumbers;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.familyhealthcare.util.ExportLocalization.text;

/** 导入专用的封闭原始事实；不进入通用实体，也不接受预览响应作为写入凭据。 */
public final class ClinicalImportFacts {
    private static final int MAX_BYTES = 4096;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final Pattern TIMESTAMP = Pattern.compile("([1-9][0-9]{3}-[0-9]{2}-[0-9]{2})(?:T([0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.([0-9]{1,6}))?)?)(Z|[+-][0-9]{2}:[0-9]{2})?)?");
    private static final String[] FHIR_TIMES = {"effectiveDateTime", "issued", "effectiveInstant"};
    private static final String[] DEVICE_TIMES = {"observedAt", "date"};
    private final Map<String,Object> view;
    private final String canonicalJson;

    private ClinicalImportFacts(Map<String,Object> value) {
        try {
            String json = JSON.writeValueAsString(value);
            if (utf8(json) > MAX_BYTES) throw rowFailure("metadata", "ORIGINAL_FACTS_TOO_LARGE");
            view = immutableMap(value);
            canonicalJson = json;
        } catch (RowFailure e) { throw e; }
        catch (Exception e) { throw rowFailure("metadata", "INVALID_LAB_FIELD"); }
    }

    public String toCanonicalJson() { return canonicalJson; }
    public Map<String,Object> toView() { return view; }
    public String kind() { return (String)view.get("kind"); }
    LocalDate localDate() { return LocalDate.parse((String)basis().get("localDate")); }
    String localTime() { return (String)basis().get("localTime"); }
    private Map<String,Object> basis() { return object(view.get("basis")); }

    public static ClinicalImportFacts fromDevice(Map<String,Object> source, String kind) {
        if (!Arrays.asList("BP","BG","BP_BG").contains(kind)) throw rowFailure("measurement", "INVALID_MEASUREMENT_VALUE");
        Problems problems = new Problems();
        Map<String,Object> root = root(source,"DEVICE",kind,problems);
        List<Map<String,Object>> readings = new ArrayList<>();
        Map<String,Object> unit = fact(source,"unit",50,200,"unit","INVALID_GLUCOSE_UNIT",problems);
        if (!"BG".equals(kind)) {
            readings.add(bpReading(source.get("systolic"),"SYSTOLIC","DEVICE_SYSTOLIC",missing(),"systolic",problems));
            readings.add(bpReading(source.get("diastolic"),"DIASTOLIC","DEVICE_DIASTOLIC",missing(),"diastolic",problems));
        }
        if (!"BP".equals(kind)) {
            readings.add(glucoseReading(source.get("glucose"),"DEVICE_GLUCOSE",unit,problems));
        }
        root.put("readings",readings); root.put("deviceUnit",unit);
        problems.requireValid();
        return new ClinicalImportFacts(root);
    }

    public static ClinicalImportFacts fromFhir(Map<String,Object> source, String kind) {
        if (!Arrays.asList("BP","BG","LAB").contains(kind)) throw rowFailure("measurement", "INVALID_MEASUREMENT_VALUE");
        Problems problems = new Problems();
        Map<String,Object> root = root(source,"FHIR",kind,problems);
        List<Map<String,Object>> readings = new ArrayList<>();
        if ("BP".equals(kind)) {
            Map<String,Map<String,Object>> found = new HashMap<>();
            for (Object c : list(source.get("component"))) {
                Map<String,Object> component=object(c);
                String code=firstCode(component);
                if ("8480-6".equals(code)||"8462-4".equals(code)) {
                    if (found.containsKey(code)) problems.add("component", "INVALID_MEASUREMENT_VALUE");
                    else found.put(code,object(component.get("valueQuantity")));
                }
            }
            String[] codes={"8480-6","8462-4"}, metrics={"SYSTOLIC","DIASTOLIC"},fields={"systolic","diastolic"};
            for(int i=0;i<2;i++) {
                Map<String,Object> q=found.containsKey(codes[i])?found.get(codes[i]):Collections.emptyMap();
                Map<String,Object> unit=fact(q,"unit",50,200,fields[i]+"Unit","INVALID_BP_UNIT",problems);
                if("STRING".equals(unit.get("state"))&&!"mmHg".equalsIgnoreCase(((String)unit.get("supplied")).trim()))problems.add(fields[i]+"Unit","INVALID_BP_UNIT");
                readings.add(bpReading(q.get("value"),metrics[i],i==0?"FHIR_COMPONENT_8480_6":"FHIR_COMPONENT_8462_4",unit,fields[i],problems));
            }
        } else {
            Map<String,Object> quantity=object(source.get("valueQuantity"));
            Map<String,Object> unit=fact(quantity,"unit",50,200,"unit","BG".equals(kind)?"INVALID_GLUCOSE_UNIT":"INVALID_LAB_FIELD",problems);
            if("BG".equals(kind)) readings.add(glucoseReading(quantity.get("value"),"FHIR_QUANTITY_VALUE",unit,problems));
            else {
                boolean numeric=!quantity.isEmpty();
                Map<String,Object> value=numeric?numeric(quantity.get("value"),"value",problems):textValue(source.get("valueString"),problems);
                Map<String,Object> reading=map("metric","LAB_SCALAR","field",numeric?"FHIR_QUANTITY_VALUE":"FHIR_VALUE_STRING","value",value,"unit",unit);
                Map<String,Object> code=object(source.get("code")); List<?> coding=list(code.get("coding"));
                Map<String,Object> first=coding.isEmpty()?Collections.emptyMap():object(coding.get(0));
                reading.put("code",fact(first,"code",50,200,"code","INVALID_LAB_FIELD",problems));
                Object d=first.get("display"), t=code.get("text");
                // 只保留实际选中的显示来源；空白不会变成虚构的本地化标签。
                if(d instanceof String&&!((String)d).trim().isEmpty()) {
                    reading.put("display",fact(first,"display",200,800,"display","INVALID_LAB_FIELD",problems));reading.put("displayField","FIRST_CODING_DISPLAY");
                } else if(t instanceof String&&!((String)t).trim().isEmpty()) {
                    reading.put("display",fact(code,"text",200,800,"display","INVALID_LAB_FIELD",problems));reading.put("displayField","CODE_TEXT");
                } else {
                    if(d!=null&&!(d instanceof String)||t!=null&&!(t instanceof String))problems.add("display","INVALID_LAB_FIELD");
                    reading.put("display",missing());reading.put("displayField","NOT_SUPPLIED");
                }
                readings.add(reading);
            }
        }
        root.put("readings",readings); problems.requireValid(); return new ClinicalImportFacts(root);
    }

    private static Map<String,Object> root(Map<String,Object> source,String format,String kind,Problems problems) {
        Map<String,Object> root=map("version",1,"format",format,"kind",kind);
        Map<String,Object> timestamps=new LinkedHashMap<>();String selected=null;Map<String,Object> selectedBasis=null;
        for(String name:"FHIR".equals(format)?FHIR_TIMES:DEVICE_TIMES) {
            Object value=source.get(name);
            Map<String,Object> f=source.containsKey(name)?value==null?map("state","NULL"):map("state","STRING","supplied",value):missing();
            timestamps.put(name,f);
            if(value!=null) {
                Map<String,Object> parsed=null;
                try { parsed=timestamp(value); } catch(RuntimeException invalid){problems.add(name,"INVALID_TIMESTAMP");}
                if(selected==null){selected=name;selectedBasis=parsed;}
            }
        }
        if(selected==null)problems.add("timestamp","MISSING_TIMESTAMP");
        root.put("timestamps",timestamps);root.put("selectedTimestamp",selected);root.put("basis",selectedBasis);return root;
    }

    private static Map<String,Object> timestamp(Object raw) {
        if(!(raw instanceof String)||((String)raw).length()>32)throw new IllegalArgumentException();
        Matcher match=TIMESTAMP.matcher((String)raw);if(!match.matches())throw new IllegalArgumentException();
        String date=match.group(1),time=match.group(2),fraction=match.group(3),offset=match.group(4);
        LocalDate.parse(date);
        if(time!=null) {
            int hour=Integer.parseInt(time.substring(0,2)),minute=Integer.parseInt(time.substring(3,5));
            if(hour>23||minute>59||time.length()>5&&Integer.parseInt(time.substring(6,8))>59)throw new IllegalArgumentException();
        }
        if(offset!=null&&!"Z".equals(offset)) {
            int h=Integer.parseInt(offset.substring(1,3)),m=Integer.parseInt(offset.substring(4,6));
            if(h>14||m>59||h==14&&m!=0||"-00:00".equals(offset))throw new IllegalArgumentException();
        }
        return map("localDate",date,"localTime",time,"precision",time==null?"DATE":fraction!=null?"FRACTION":time.length()==5?"MINUTE":"SECOND",
                "fractionDigits",fraction==null?0:fraction.length(),"offsetState",offset==null?"NOT_SUPPLIED":"EXPLICIT","offset",offset);
    }

    private static Map<String,Object> bpReading(Object raw,String metric,String field,Map<String,Object> unit,String name,Problems problems) {
        Map<String,Object> value=numeric(raw,name,problems);
        try {
            int n=MeasurementInputNumbers.requireImportInteger(raw,name);
            if(n<("SYSTOLIC".equals(metric)?40:20)||n>("SYSTOLIC".equals(metric)?300:200))problems.add(name,"INVALID_MEASUREMENT_VALUE");
        } catch(IllegalArgumentException e){problems.add(name,"INVALID_EXACT_INTEGER");}
        return map("metric",metric,"field",field,"value",value,"unit",unit);
    }

    private static Map<String,Object> glucoseReading(Object raw,String field,Map<String,Object> unit,Problems problems) {
        Map<String,Object> value=numeric(raw,"glucose",problems);
        try {
            BigDecimal number=new BigDecimal((String)value.get("exact"));
            MeasurementInputNumbers.selfGlucose(number);
            if(number.signum()<=0)throw new IllegalArgumentException();
        } catch(RuntimeException e){problems.add("glucose","INVALID_MEASUREMENT_VALUE");}
        try {
            if(!"STRING".equals(unit.get("state")))throw new IllegalArgumentException();
            String supplied=(String)unit.get("supplied"),trimmed=supplied.trim();
            if(utf8(supplied)>32||trimmed.length()>10||!("mmol/L".equalsIgnoreCase(trimmed)||"mg/dL".equalsIgnoreCase(trimmed)))throw new IllegalArgumentException();
        } catch(RuntimeException e){problems.add("unit","INVALID_GLUCOSE_UNIT");}
        return map("metric","GLUCOSE","field",field,"value",value,"unit",unit);
    }

    private static Map<String,Object> numeric(Object raw,String field,Problems problems) {
        try {
            if(!(raw instanceof Number)&&!(raw instanceof String))throw new IllegalArgumentException();
            String supplied=raw.toString();if(supplied.length()>256||utf8(supplied)>1024)throw new IllegalArgumentException();
            BigDecimal value=new BigDecimal(raw instanceof String?supplied.trim():supplied);
            // toString 保持有限科学计数法，绝不展开巨大指数或经过二进制浮点。
            String exact=value.toString();if(exact.length()>256)throw new IllegalArgumentException();
            return raw instanceof String?map("type","NUMERIC_STRING","exact",exact,"supplied",supplied):map("type","JSON_NUMBER","exact",exact);
        } catch(RuntimeException e){problems.add(field,"INVALID_MEASUREMENT_VALUE");return Collections.emptyMap();}
    }
    private static Map<String,Object> textValue(Object raw,Problems problems) {
        try {bounded(raw,500,2000);return map("type","TEXT","supplied",raw);}
        catch(RuntimeException e){problems.add("value","INVALID_LAB_FIELD");return Collections.emptyMap();}
    }
    private static Map<String,Object> fact(Map<String,Object> source,String key,int chars,int bytes,String field,String code,Problems problems) {
        if(!source.containsKey(key))return missing();if(source.get(key)==null)return map("state","NULL");
        try {bounded(source.get(key),chars,bytes);return map("state","STRING","supplied",source.get(key));}
        catch(RuntimeException e){problems.add(field,code);return missing();}
    }
    private static Map<String,Object> missing(){return map("state","MISSING");}
    private static void bounded(Object raw,int chars,int bytes){if(!(raw instanceof String)||((String)raw).length()>chars||utf8((String)raw)>bytes)throw new IllegalArgumentException();}
    static int utf8(String value) {
        try { return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining(); }
        catch(Exception e){throw new IllegalArgumentException();}
    }

    /** 解码只接受本编解码器的完整规范形状；校验原始事实，不和后来更正的当前数值比较。 */
    public static ClinicalImportFacts decode(String json,String nativeSourceType) {
        try {
            if(json==null||utf8(json)>MAX_BYTES)throw new IllegalArgumentException();
            Map<String,Object> root=JSON.readValue(json,new TypeReference<Map<String,Object>>(){});
            String format=(String)root.get("format"),kind=(String)root.get("kind");
            if(!Objects.equals(format,nativeSourceType))throw new IllegalArgumentException();
            Map<String,Object> raw=new LinkedHashMap<>();Map<String,Object> timestamps=object(root.get("timestamps"));
            for(String name:"FHIR".equals(format)?FHIR_TIMES:DEVICE_TIMES) restore(raw,name,object(timestamps.get(name)));
            List<Object> components=new ArrayList<>();
            for(Object entry:list(root.get("readings"))) {
                Map<String,Object> reading=object(entry),value=object(reading.get("value"));
                String metric=(String)reading.get("metric"),type=(String)value.get("type");
                Object scalar="JSON_NUMBER".equals(type)?new BigDecimal((String)value.get("exact")):value.get("supplied");
                if("DEVICE".equals(format))raw.put("SYSTOLIC".equals(metric)?"systolic":"DIASTOLIC".equals(metric)?"diastolic":"glucose",scalar);
                else {
                    Map<String,Object> quantity=map("value",scalar);restore(quantity,"unit",object(reading.get("unit")));
                    if("BP".equals(kind))components.add(map("code",map("coding",Collections.singletonList(map("code","SYSTOLIC".equals(metric)?"8480-6":"8462-4"))),"valueQuantity",quantity));
                    else if("FHIR_VALUE_STRING".equals(reading.get("field")))raw.put("valueString",scalar);
                    else raw.put("valueQuantity",quantity);
                    if("LAB".equals(kind)) {
                        Map<String,Object> first=new LinkedHashMap<>(),code=new LinkedHashMap<>();restore(first,"code",object(reading.get("code")));
                        if("FIRST_CODING_DISPLAY".equals(reading.get("displayField")))restore(first,"display",object(reading.get("display")));
                        if("CODE_TEXT".equals(reading.get("displayField")))restore(code,"text",object(reading.get("display")));
                        code.put("coding",Collections.singletonList(first));raw.put("code",code);
                    }
                }
            }
            if("BP".equals(kind))raw.put("component",components);
            if("DEVICE".equals(format))restore(raw,"unit",object(root.get("deviceUnit")));
            ClinicalImportFacts facts="FHIR".equals(format)?fromFhir(raw,kind):"DEVICE".equals(format)?fromDevice(raw,kind):null;
            if(facts==null||!facts.canonicalJson.equals(json))throw new IllegalArgumentException();
            return facts;
        } catch(Exception e){throw Failure.unavailable();}
    }
    private static void restore(Map<String,Object> target,String key,Map<String,Object> fact) {
        if("NULL".equals(fact.get("state")))target.put(key,null);
        else if("STRING".equals(fact.get("state")))target.put(key,fact.get("supplied"));
        else if(!"MISSING".equals(fact.get("state")))throw new IllegalArgumentException();
    }

    public void requireBpProjection(BpSelfMonitorRecord row) {
        String type="BP_BG".equals(kind())?"BP_BG":kind();
        if("LAB".equals(kind())||!Objects.equals(type,"BOTH".equals(row.getMeasureType())?"BP_BG":row.getMeasureType())
                ||!Objects.equals(localDate(),row.getRecordDate())||!Objects.equals(localTime(),row.getRecordTime())
                ||!Objects.equals(view.get("format"),row.getSourceType())||row.getSourceExternalId()==null||row.getSourceExternalId().trim().isEmpty())throw rowFailure("metadata","INVALID_MEASUREMENT_VALUE");
        Integer sys=null,dia=null;BigDecimal glucose=null;String unit=null;
        for(Object r:list(view.get("readings"))) {
            Map<String,Object> reading=object(r);BigDecimal number=new BigDecimal((String)object(reading.get("value")).get("exact"));
            if("SYSTOLIC".equals(reading.get("metric")))sys=number.intValueExact();
            if("DIASTOLIC".equals(reading.get("metric")))dia=number.intValueExact();
            if("GLUCOSE".equals(reading.get("metric"))){glucose=number;unit=((String)object(reading.get("unit")).get("supplied")).trim();}
        }
        if(!Objects.equals(sys,row.getSystolicBp())||!Objects.equals(dia,row.getDiastolicBp())||MeasurementInputNumbers.changed(glucose,row.getBloodGlucose())||!Objects.equals(unit,row.getBgUnit()))throw rowFailure("metadata","INVALID_MEASUREMENT_VALUE");
        decode(canonicalJson,row.getSourceType());
    }
    public void requireLabProjection(MedicalRecord row,MedicalRecordItem item) {
        if(!"LAB".equals(kind())||!Objects.equals(localDate(),row.getRecordDate())||!Objects.equals(view.get("format"),row.getSourceType())||row.getSourceExternalId()==null||row.getSourceExternalId().trim().isEmpty())throw rowFailure("metadata","INVALID_LAB_FIELD");
        Map<String,Object> r=object(list(view.get("readings")).get(0)),v=object(r.get("value"));
        String result="JSON_NUMBER".equals(v.get("type"))?(String)v.get("exact"):(String)v.get("supplied");
        String code=trimmedFact(object(r.get("code"))),display=trimmedFact(object(r.get("display")));
        if(display==null)display=code==null?text("Imported observation","导入的观测记录"):code;
        if(!Objects.equals(result,item.getResultValue())||!Objects.equals(code,item.getItemCode())||!Objects.equals(display,item.getItemName())||!Objects.equals(trimmedFact(object(r.get("unit"))),item.getUnit()))throw rowFailure("metadata","INVALID_LAB_FIELD");
        decode(canonicalJson,row.getSourceType());
    }
    static String trimmedFact(Map<String,Object> f){if(!"STRING".equals(f.get("state")))return null;String s=((String)f.get("supplied")).trim();return s.isEmpty()?null:s;}

    public static void rejectOrdinaryClientFacts(Object body) { scan(body,false); }
    static void rejectImportMetadata(Object body) { scan(body,true); }
    private static void scan(Object body,boolean importing) {
        if(!(body instanceof Map)&&!(body instanceof List))return;
        Deque<Frame> stack=new ArrayDeque<>();stack.push(new Frame(body,1));int count=1;
        while(!stack.isEmpty()) {
            Frame frame=stack.peek();if(!frame.iterator.hasNext()){stack.pop();continue;}
            if(!importing&&++count>10000)throw Failure.complex();
            Object value=frame.iterator.next();
            if(frame.map) {
                Map.Entry<?,?> entry=(Map.Entry<?,?>)value;String key=String.valueOf(entry.getKey()).replace("_","").toLowerCase(Locale.ROOT);value=entry.getValue();
                if(Arrays.asList("importfacts","importfactsjson","originalimport").contains(key))throw Failure.forbidden();
                if(importing&&(key.startsWith("workflow")||Arrays.asList("sourceidentityid","sourcerevision","sourcestatus","correctionreason","followups","alertevent","alertevidence","alertcommand","measurementcommand","commandkey","expectedversion").contains(key)
                        ||"alerttype".equals(key)&&"MEASUREMENT_FOLLOW_UP".equalsIgnoreCase(String.valueOf(value).trim())))throw new IllegalArgumentException("Workflow metadata cannot be imported as a raw clinical observation.");
            }
            if(value instanceof Map||value instanceof List) {
                if(frame.depth>=64||!importing&&++count>10000)throw Failure.complex();
                stack.push(new Frame(value,frame.depth+1));
            }
        }
    }
    private static final class Frame {
        final Iterator<?> iterator;final boolean map;final int depth;
        Frame(Object value,int depth){this.depth=depth;map=value instanceof Map;iterator=map?((Map<?,?>)value).entrySet().iterator():((List<?>)value).iterator();}
    }

    /** 只有本包解析器能够建立可信行；返回的预览没有反向构造入口。 */
    public static final class PreparedRow {
        private final Map<String,Object> nativeFields;
        private final ClinicalImportFacts facts;
        PreparedRow(Map<String,Object> nativeFields,ClinicalImportFacts facts){this.nativeFields=immutableMap(nativeFields);this.facts=Objects.requireNonNull(facts);}
        Map<String,Object> fields(){return nativeFields;}
        ClinicalImportFacts facts(){return facts;}
        public Map<String,Object> toPreview(){Map<String,Object> out=new LinkedHashMap<>(nativeFields);out.put("originalImport",map("facts",facts.toView()));return out;}
    }
    public static final class Failure extends RuntimeException {
        private final int status;private final String code;
        private Failure(int status,String code,String en,String cn){super(text(en,cn),null,false,false);this.status=status;this.code=code;}
        public int getStatus(){return status;}public String getCode(){return code;}
        public static Failure forbidden(){return new Failure(400,"CLIENT_IMPORT_FACTS_FORBIDDEN","Original import facts are server-managed.","导入原始事实由服务器管理。");}
        public static Failure complex(){return new Failure(400,"IMPORT_FACTS_INPUT_TOO_COMPLEX","The request is too complex to validate safely.","请求结构过于复杂，无法安全验证。");}
        public static Failure notReady(){return new Failure(503,"IMPORT_FACTS_NOT_READY","The database is not ready to preserve original import facts. No import was started.","数据库尚不具备保留导入原始事实的条件，本次导入未开始。");}
        public static Failure unavailable(){return new Failure(503,"IMPORT_FACTS_UNAVAILABLE","Original import facts could not be loaded safely.","暂时无法安全读取导入原始事实。");}
        public static Failure immutable(){return new Failure(409,"ORIGINAL_IMPORT_BINDING_IMMUTABLE","The original import source binding cannot be changed.","不能更改原始导入的来源关联。");}
    }
    /** 只识别自有触发器的固定 SQLSTATE/令牌，不向外传递带参数的 JDBC 异常。 */
    public static RuntimeException translateBinding(RuntimeException error) {
        Throwable cause=error;int remaining=16;
        while(cause!=null&&remaining-->0) {
            if(cause instanceof SQLException) {
                SQLException sql=(SQLException)cause;
                if("45000".equals(sql.getSQLState())&&"IF57_ORIGINAL_BINDING_IMMUTABLE".equals(sql.getMessage()))return Failure.immutable();
            }
            cause=cause.getCause();
        }
        return error;
    }
    static final class RowFailure extends IllegalArgumentException {
        final List<Map<String,Object>> fields;
        RowFailure(List<Map<String,Object>> fields){super(text("This row cannot be imported without changing or losing supplied facts.","此行无法在不更改或丢失所提供事实的情况下导入。"));this.fields=fields;}
        boolean onlyExactBp(){for(Map<String,Object> field:fields)if(!Arrays.asList("systolic","diastolic").contains(field.get("field"))||!"INVALID_EXACT_INTEGER".equals(field.get("code")))return false;return fields.stream().anyMatch(f->"INVALID_EXACT_INTEGER".equals(f.get("code")));}
    }
    private static RowFailure rowFailure(String field,String code){return new RowFailure(Collections.singletonList(map("field",field,"code",code)));}
    private static final class Problems {
        final List<Map<String,Object>> fields=new ArrayList<>();
        void add(String field,String code){
            for(Map<String,Object> issue:fields)if(field.equals(issue.get("field"))){
                if("INVALID_EXACT_INTEGER".equals(code))issue.put("code",code);
                return;
            }
            fields.add(map("field",field,"code",code));
        }
        void requireValid(){
            if(fields.isEmpty())return;
            // 固定展示次序与最多八个问题；一个原字段只计一次，避免误导部分失败计数。
            final List<String> order=Arrays.asList("effectiveDateTime","issued","effectiveInstant","observedAt","date","timestamp","component","systolic","diastolic","glucose","value","systolicUnit","diastolicUnit","unit","code","display","metadata");
            fields.sort(Comparator.comparingInt(f->order.indexOf(f.get("field"))));
            throw new RowFailure(Collections.unmodifiableList(new ArrayList<>(fields.subList(0,Math.min(8,fields.size())))));
        }
    }
    static String firstCode(Map<String,Object> source){List<?> coding=list(object(source.get("code")).get("coding"));return coding.isEmpty()?null:trim(object(coding.get(0)).get("code"));}
    static String trim(Object value){if(value==null)return null;String s=String.valueOf(value).trim();return s.isEmpty()||"null".equalsIgnoreCase(s)?null:s;}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap();}
    static List<?> list(Object value){return value instanceof List?(List<?>)value:Collections.emptyList();}
    static Map<String,Object> map(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static Map<String,Object> immutableMap(Map<String,Object> value){Map<String,Object> out=new LinkedHashMap<>();for(Map.Entry<String,Object> e:value.entrySet())out.put(e.getKey(),immutable(e.getValue()));return Collections.unmodifiableMap(out);}
    private static Object immutable(Object value){if(value instanceof Map)return immutableMap(object(value));if(value instanceof List){List<Object> out=new ArrayList<>();for(Object item:(List<?>)value)out.add(immutable(item));return Collections.unmodifiableList(out);}return value;}
}
