package org.familyhealthcare.service.measurementread;

import org.familyhealthcare.service.alertworkflow.AlertWorkflowException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;

/** 有界原值页不等于可信判定；未加载的备注不能由列表猜成空串。 */
@Service
public class OriginalMeasurementReadService {
    private final OriginalMeasurementRepository repo;
    private final OriginalMeasurementReadAuthorization auth;
    private final org.familyhealthcare.service.ClinicalImportFactsRepository factsRepository;
    public OriginalMeasurementReadService(OriginalMeasurementRepository repo, OriginalMeasurementReadAuthorization auth) { this(repo, auth, null); }
    @org.springframework.beans.factory.annotation.Autowired
    public OriginalMeasurementReadService(OriginalMeasurementRepository repo, OriginalMeasurementReadAuthorization auth,
            org.familyhealthcare.service.ClinicalImportFactsRepository factsRepository) {
        this.repo = repo; this.auth = auth; this.factsRepository = factsRepository;
    }
    public Map<String,Object> selfPage(long actor,long patient,String filter,String from,String to,String cursor,int limit){return page(actor,patient,true,filter,from,to,cursor,limit);}
    public Map<String,Object> journeyPage(long actor,long patient,String filter,String from,String to,String cursor,int limit){return page(actor,patient,false,filter,from,to,cursor,limit);}
    private Map<String,Object> page(long actor,long patient,boolean self,String filter,String from,String to,String cursor,int limit){
        if(patient<=0||limit<1||limit>100)throw OriginalMeasurementReadAuthorization.invalid();
        if(filter!=null&&!(self?Arrays.asList("BP","BG","BP_BG","BOTH"):Arrays.asList("BP","GLUCOSE","SPO2","WEIGHT","HEART_RATE","TEMPERATURE","CUSTOM")).contains(filter))throw OriginalMeasurementReadAuthorization.invalid();
        if(self&&"BOTH".equals(filter))filter="BP_BG";
        if((from==null)!=(to==null))throw OriginalMeasurementReadAuthorization.invalid();LocalDate start=from==null?null:date(from),end=to==null?null:date(to);
        if(start!=null&&(ChronoUnit.DAYS.between(start,end)<0||ChronoUnit.DAYS.between(start,end)>=90||end.equals(LocalDate.of(9999,12,31))))throw OriginalMeasurementReadAuthorization.invalid();
        OriginalMeasurementCursor codec=new OriginalMeasurementCursor(actor,patient,self,filter,start,end);Map<String,Object>position=codec.decode(cursor);
        try{
            boolean mode=auth.beginRaw(actor,patient,self);List<Map<String,Object>>rows=repo.page(patient,self,filter,start,end,position,limit);
            boolean more=rows.size()>limit;List<Map<String,Object>>items=new ArrayList<>(rows.subList(0,Math.min(limit,rows.size())));String next=more?codec.encode(items.get(items.size()-1)):null;
            auth.finishRaw(actor,patient,self,mode);
            return map("items",items,"nextCursor",next,"coverage",map("scope","CURRENT_PAGE","consistency","NON_SNAPSHOT","sourceType",self?"BP_SELF_MONITOR":"HEALTH_MEASUREMENT","from",from,"to",to,"limit",limit,"unknownDateExcluded",start!=null,"sort",self?OriginalMeasurementCursor.SELF_ORDER:OriginalMeasurementCursor.JOURNEY_ORDER));
        }catch(AlertWorkflowException failure){throw failure;}catch(DataAccessException|IllegalStateException failure){throw OriginalMeasurementReadAuthorization.unavailable();}
    }
    public Map<String,Object> selfDetail(long actor,long patient,long record){
        if(patient<=0||record<=0)throw OriginalMeasurementReadAuthorization.invalid();
        try{boolean mode=auth.beginRaw(actor,patient,true);Map<String,Object>row=repo.detail(patient,record);auth.finishRaw(actor,patient,true,mode);return row;}
        catch(AlertWorkflowException failure){throw failure;}catch(DataAccessException|IllegalStateException failure){throw OriginalMeasurementReadAuthorization.unavailable();}
    }
    /** 仅详情按需读取一次事实；普通列表和旧详情不访问新增列。 */
    public Map<String,Object> selfDetail(long actor, long patient, long record, boolean includeImportFacts) {
        if (!includeImportFacts) return selfDetail(actor, patient, record);
        if (patient <= 0 || record <= 0) throw OriginalMeasurementReadAuthorization.invalid();
        try {
            boolean mode = auth.beginRaw(actor, patient, true);
            Map<String,Object> row = repo.detail(patient, record);
            row.put("originalImport", factsRepository.readBpOriginal(patient, record));
            auth.finishRaw(actor, patient, true, mode);
            return row;
        } catch (AlertWorkflowException failure) { throw failure;
        } catch (RuntimeException failure) { throw org.familyhealthcare.service.ClinicalImportFacts.Failure.unavailable(); }
    }
    static LocalDate date(String text){try{if(text==null||!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();LocalDate d=LocalDate.parse(text);if(d.getYear()<1000)throw new IllegalArgumentException();return d;}catch(RuntimeException failure){throw OriginalMeasurementReadAuthorization.invalid();}}
}
