package org.familyhealthcare.service;

import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.dao.DuplicateKeyException;
import java.math.BigDecimal;
import java.util.*;
import static org.familyhealthcare.service.ClinicalImportFacts.*;
import static org.familyhealthcare.util.ExportLocalization.text;

@Service
public class ClinicalImportService {
    @Autowired private DataScopeHelper scope;
    @Autowired private ClinicalImportRowWriter rowWriter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClinicalImportFactsRepository factsRepository;

    public Map<String,Object> preview(Long patientId,Map<String,Object> payload) {
        scope.requirePatient(patientId);
        ParsedRows parsed=parseRows(payload);
        factsRepository.requireReady();
        List<Map<String,Object>> items=new ArrayList<>();
        for(PreparedRow row:parsed.rows)items.add(row.toPreview());
        Map<String,Object> out=map("items",items,"warnings",parsed.warnings,"itemCount",items.size(),
                "rejectedCount",parsed.rejected.size(),"rejectedRows",parsed.rejected,"requiresConfirmation",true);
        workflowCoverage(out);
        out.put("supported",Arrays.asList("FHIR Observation",text("blood pressure","血压"),text("blood glucose","血糖"),text("laboratory value","化验结果"),text("device reading JSON","设备读数 JSON")));
        return out;
    }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Map<String,Object> commit(Long patientId,Map<String,Object> payload) {
        Patient patient=scope.requirePatient(patientId);
        // 重新解析原始请求；预览 DTO 从来不是持久化权限。
        ParsedRows parsed=parseRows(payload);
        factsRepository.requireReady();
        int created=0,skipped=0,errors=parsed.rejected.size();List<String> messages=new ArrayList<>();
        for(Map<String,Object> rejected:parsed.rejected)messages.add((String)rejected.get("message"));
        for(PreparedRow row:parsed.rows) {
            try {
                if(rowWriter.write(patientId,patient.getName(),row))created++;else skipped++;
            } catch(DuplicateKeyException e) {
                boolean duplicate=false;
                try {duplicate=rowWriter.exists(patientId,row.fields());}catch(Exception unavailable){/* 使用固定诊断，继续其他行。 */}
                if(duplicate)skipped++;
                else {errors++;messages.add(text("This import row could not be saved after the duplicate check. Review the import result before retrying.","重复记录核验后，此导入行仍未能保存。请核验导入结果后再重试。"));}
            } catch(Exception e) {
                errors++;messages.add(text("This import row could not be saved. Other valid rows may have been imported.","此导入行未能保存，其他有效行可能已导入。"));
            }
        }
        String source=payload.get("bundle") instanceof Map?"FHIR":"DEVICE";
        jdbc.update("INSERT INTO clinical_import_batch(user_id,patient_id,source_type,status,item_count,error_count,summary) VALUES(?,?,?,?,?,?,?)",
                scope.requireUserId(),patientId,source,errors==0?"COMPLETED":"COMPLETED_WITH_ERRORS",created,errors,
                text("Created ","已创建 ")+created+text(", skipped duplicates ","，跳过重复记录 ")+skipped+text(", errors ","，错误 ")+errors);
        Map<String,Object> out=map("created",created,"skipped",skipped,"errors",errors,"messages",messages,"requiresConfirmation",false,"verificationStatus","REVIEW_REQUIRED");
        workflowCoverage(out);return out;
    }

    private ParsedRows parseRows(Map<String,Object> payload) {
        rejectImportMetadata(payload);
        ParsedRows parsed=new ParsedRows();
        Map<String,Object> bundle=object(payload.get("bundle"));Object device=payload.get("readings");
        if(device instanceof Map)device=object(device).get("readings");
        if(list(bundle.get("entry")).size()+list(device).size()>1000)throw new IllegalArgumentException(text("The import request exceeds the supported row limit.","导入请求超出支持的行数限制。"));
        if(payload.get("bundle") instanceof Map) {
            if(!"Bundle".equals(bundle.get("resourceType")))parsed.warnings.add(text("The FHIR payload is not a Bundle.","FHIR 数据不是 Bundle 资源。"));
            else {
                int index=0;
                for(Object entry:list(bundle.get("entry"))) {
                    index++;Map<String,Object> observation=object(object(entry).get("resource"));
                    if("Observation".equals(observation.get("resourceType")))parseObservation(observation,index,parsed);
                }
            }
        }
        int index=0;
        for(Object value:list(device)) {
            index++;Map<String,Object> reading=object(value);
            boolean bp=reading.containsKey("systolic")||reading.containsKey("diastolic"),bg=reading.get("glucose")!=null;
            if(!bp&&!bg){parsed.warnings.add(text("Device reading ","设备读数 ")+index+text(" has no supported measurement."," 没有支持的测量数据。"));continue;}
            try {
                ClinicalImportFacts facts=fromDevice(reading,bp?bg?"BP_BG":"BP":"BG");
                String external=trim(reading.get("sourceExternalId"));
                if(external==null)external="device-"+index+"-"+facts.localDate()+"T"+facts.localTime();
                parsed.rows.add(prepare(facts,external,null));
            } catch(RowFailure invalid){reject(parsed,"DEVICE",index,invalid);}
        }
        if(parsed.rows.isEmpty()&&parsed.warnings.isEmpty())parsed.warnings.add(text("No supported observations were found. Provide a FHIR Bundle or device readings.","未找到支持的观测数据，请提供 FHIR Bundle 或设备读数。"));
        return parsed;
    }

    private void parseObservation(Map<String,Object> observation,int index,ParsedRows parsed) {
        String code=firstCode(observation);Map<String,Object> c=object(observation.get("code"));List<?> coding=list(c.get("coding"));
        String display=coding.isEmpty()?null:trim(object(coding.get(0)).get("display"));if(display==null)display=trim(c.get("text"));
        boolean bp="85354-9".equals(code);
        for(Object component:list(observation.get("component")))if(Arrays.asList("8480-6","8462-4").contains(firstCode(object(component))))bp=true;
        Map<String,Object> quantity=object(observation.get("valueQuantity"));
        if(!bp&&quantity.isEmpty()&&observation.get("valueString")==null) {
            // 不把外部 ID 或任何原始载荷插入诊断。
            parsed.warnings.add("FHIR "+index+": "+text("The observation has no supported scalar value.","此观测记录没有支持的标量值。"));return;
        }
        String label=display==null?code:display;
        boolean bg=Arrays.asList("2339-0","2345-7","15074-8").contains(code)||label!=null&&label.toLowerCase(Locale.ROOT).contains("glucose");
        try {
            ClinicalImportFacts facts=fromFhir(observation,bp?"BP":bg?"BG":"LAB");
            String external=trim(observation.get("id"));if(external==null)external="observation-"+UUID.randomUUID();
            parsed.rows.add(prepare(facts,external,observation));
        } catch(RowFailure invalid){reject(parsed,"FHIR",index,invalid);}
    }

    private PreparedRow prepare(ClinicalImportFacts facts,String external,Map<String,Object> observation) {
        boolean lab="LAB".equals(facts.kind());
        Map<String,Object> row=map("kind",lab?"LAB":"VITAL","sourceType",facts.toView().get("format"),"externalId",external,"date",facts.localDate(),"time",facts.localTime());
        if(!lab){row.put("measureType","BP_BG".equals(facts.kind())?"BOTH":facts.kind());row.put("unit",null);}
        for(Object item:list(facts.toView().get("readings"))) {
            Map<String,Object> r=object(item),value=object(r.get("value"));String metric=(String)r.get("metric");
            if("SYSTOLIC".equals(metric))row.put("systolic",new BigDecimal((String)value.get("exact")).intValueExact());
            else if("DIASTOLIC".equals(metric))row.put("diastolic",new BigDecimal((String)value.get("exact")).intValueExact());
            else if("GLUCOSE".equals(metric)){row.put("glucose",new BigDecimal((String)value.get("exact")));row.put("unit",trimmedFact(object(r.get("unit"))));}
            else {
                String code=trimmedFact(object(r.get("code"))),display=trimmedFact(object(r.get("display")));
                row.put("code",code);row.put("display",display!=null?display:code!=null?code:text("Imported observation","导入的观测记录"));
                row.put("value","JSON_NUMBER".equals(value.get("type"))?value.get("exact"):value.get("supplied"));row.put("unit",trimmedFact(object(r.get("unit"))));
                row.put("referenceRange",referenceRange(observation));row.put("confidence",BigDecimal.ONE);
            }
        }
        return new PreparedRow(row,facts);
    }
    private void reject(ParsedRows parsed,String source,int index,RowFailure failure) {
        boolean bp=failure.onlyExactBp();List<Map<String,Object>> fields=failure.fields;
        if(bp) {
            fields=new ArrayList<>();Set<Object> names=new LinkedHashSet<>();for(Map<String,Object> f:failure.fields)if("INVALID_EXACT_INTEGER".equals(f.get("code")))names.add(f.get("field"));
            for(Object name:names)fields.add(map("field",name,"code","INVALID_EXACT_INTEGER"));
        }
        if(!bp) {
            List<Map<String,Object>> normalized=new ArrayList<>();
            for(Map<String,Object> field:fields)normalized.add(map("field",field.get("field"),"code","INVALID_EXACT_INTEGER".equals(field.get("code"))?"INVALID_MEASUREMENT_VALUE":field.get("code")));
            fields=normalized;
        }
        String message=bp?text("Both blood-pressure values must be exact 32-bit integers. This row was rejected.","收缩压和舒张压都必须是可精确保存的 32 位整数，此行已拒绝。"):failure.getMessage();
        parsed.rejected.add(map("sourceType",source,"rowIndex",index,"code",bp?"INVALID_BP_VALUES":"INVALID_IMPORT_FACTS","fields",fields,"message",message));parsed.warnings.add(message);
    }
    private String referenceRange(Map<String,Object> resource) {
        List<?> ranges=list(resource.get("referenceRange"));if(ranges.isEmpty())return null;
        Map<String,Object> range=object(ranges.get(0)),low=object(range.get("low")),high=object(range.get("high"));
        if(low.isEmpty()&&high.isEmpty())return trim(range.get("text"));
        return trim(low.get("value"))+"-"+trim(high.get("value"))+" "+Optional.ofNullable(trim(high.get("unit"))).orElse("");
    }
    private void workflowCoverage(Map<String,Object> out) {
        out.put("workflowCoverage","UNTRACKED_HISTORICAL_COPY");out.put("workflowNotice",text("Imported readings are independent historical copies without measurement follow-up history. A permitted reading can be explicitly adopted for follow-up.","导入测量为独立历史副本，不含测量随访历史；有权限的测量可明确纳入随访。"));
    }
    private static final class ParsedRows {
        final List<PreparedRow> rows=new ArrayList<>();final List<String> warnings=new ArrayList<>();final List<Map<String,Object>> rejected=new ArrayList<>();
    }
}
