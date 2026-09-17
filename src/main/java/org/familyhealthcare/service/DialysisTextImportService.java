package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.Data;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.*;

@Service
public class DialysisTextImportService {
    @Autowired private DataScopeHelper scope;
    @Autowired private DialysisRecordMapper records;
    @Autowired private PatientMapper patients;
    @Autowired private DialysisRecordService service;
    @Data public static class Preview {
        private List<DialysisRecord> rows=new ArrayList<>();
        private List<String> errors=new ArrayList<>();
        private List<String> warnings=new ArrayList<>();
        private List<String> existingDates=new ArrayList<>();
        private Map<String,List<String>> inferredFields=new LinkedHashMap<>();
        @com.fasterxml.jackson.annotation.JsonIgnore private Set<String> explicitIncompleteDates=new HashSet<>();
    }

    public Preview parse(String text){
        Preview out=new Preview();if(text==null||text.trim().isEmpty()){out.errors.add("Paste at least one dialysis record.");return out;}
        if(text.length()>100000){out.errors.add("A single import may contain up to 100,000 characters. Split larger imports into batches.");return out;}
        DialysisRecord current=null;Set<LocalDate>dates=new HashSet<>();Set<String>fields=new HashSet<>();int line=0;
        for(String raw:text.replace("\r","").split("\n")){line++;String value=raw.trim();if(value.isEmpty()||value.startsWith("#"))continue;
            for(String cell:value.split("[;；|]",-1)){cell=cell.trim();if(cell.isEmpty())continue;String[]pair=cell.split("\\s*[:：]\\s*",2);
                if(pair.length!=2){out.errors.add("Line "+line+" could not be parsed. Use 'Field: value': "+cell);continue;}
                String key=pair[0].trim(),v=pair[1].trim();
                try{
                    if("Date".equals(key)){LocalDate day=LocalDate.parse(v);if(!dates.add(day))throw new IllegalArgumentException("The same date appears more than once.");current=new DialysisRecord();current.setRecordDate(day);out.rows.add(current);fields.clear();continue;}
                    if(current==null)throw new IllegalArgumentException("Enter a date before the record fields.");
                    if(!fields.add(key))throw new IllegalArgumentException("Duplicate field for the same date: "+key);
                    if("Notes".equals(key)||"Missing reason".equals(key)){current.setRemark((current.getRemark()==null?"":current.getRemark()+"; ")+v);continue;}
                    if("Status".equals(key)){if(!Arrays.asList("Normal","Missing","Partially missing","Data missing").contains(v))throw new IllegalArgumentException("Status must be Normal, Missing, Partially missing, or Data missing.");if(!"Normal".equals(v)){current.setRecordType("INCOMPLETE");out.explicitIncompleteDates.add(current.getRecordDate().toString());}continue;}
                    if(!Arrays.asList("Previous post-dialysis weight","Pre-dialysis weight","Post-dialysis weight","Fluid removed","Interval days","Blood pressure").contains(key))throw new IllegalArgumentException("Unrecognized field: "+key);
                    if(v.isEmpty()||Arrays.asList("missing","unknown","not measured","none","-","?").contains(v.toLowerCase(java.util.Locale.ROOT)))continue;
                    if("Blood pressure".equals(key)){String[]bp=v.replace("mmHg","").trim().split("[/／]");if(bp.length!=2)throw new IllegalArgumentException("Enter blood pressure as 120/80, or leave it blank.");current.setSystolicBp(positive(bp[0]).intValueExact());current.setDiastolicBp(positive(bp[1]).intValueExact());}
                    else if("Interval days".equals(key))current.setIntervalDays(positive(v.replace("days","").trim()).intValueExact());
                    else {BigDecimal n=new BigDecimal(v.replace("kg","").trim());if(n.signum()<0||(!"Fluid removed".equals(key)&&n.signum()==0))throw new IllegalArgumentException("Enter a valid non-negative value; weights must be greater than zero.");switch(key){case "Previous post-dialysis weight":current.setLastOffWeight(n);break;case "Pre-dialysis weight":current.setOnWeight(n);break;case "Post-dialysis weight":current.setOffWeight(n);break;case "Fluid removed":current.setUfAmount(n);break;}}
                }catch(Exception e){out.errors.add("Line "+line+" ("+key+"): "+(e instanceof IllegalArgumentException?e.getMessage():"Invalid numeric value."));}
            }
        }
        out.rows.sort(Comparator.comparing(DialysisRecord::getRecordDate));
        classifyRows(out);
        if(out.rows.isEmpty())out.errors.add("No dated records were recognized.");if(out.rows.size()>366)out.errors.add("A single import may contain up to 366 records.");
        return out;
    }
    private BigDecimal positive(String s){BigDecimal n=new BigDecimal(s.trim());if(n.signum()<=0)throw new IllegalArgumentException("The value must be greater than zero.");return n;}
    public Preview preview(Long pid,String text){
        if(scope.requirePatient(pid)==null)throw new IllegalArgumentException("Select a family member.");
        Preview out=parse(text);
        List<DialysisRecord> history=records.selectList(new QueryWrapper<DialysisRecord>().eq("patient_id",pid).orderByAsc("record_date").orderByAsc("id"));
        if(history==null)history=new ArrayList<>();
        List<DialysisRecord> previous=new ArrayList<>(history);
        out.getWarnings().clear();
        for(DialysisRecord r:out.rows){
            r.setPatientId(pid);
            if(records.selectCount(new QueryWrapper<DialysisRecord>().eq("patient_id",pid).eq("record_date",r.getRecordDate()))>0)out.existingDates.add(r.getRecordDate().toString());
            inferPreviewFields(r,previous,out);
            previous.add(r);
        }
        classifyRows(out);
        return out;
    }

    private void inferPreviewFields(DialysisRecord row,List<DialysisRecord> candidates,Preview out){
        DialysisRecord prior=null;
        for(DialysisRecord candidate:candidates){
            if(candidate.getRecordDate()==null||!candidate.getRecordDate().isBefore(row.getRecordDate()))continue;
            if(prior==null||candidate.getRecordDate().isAfter(prior.getRecordDate()))prior=candidate;
        }
        if(row.getLastOffWeight()==null&&prior!=null&&prior.getOffWeight()!=null){row.setLastOffWeight(prior.getOffWeight());markInferred(out,row,"lastOffWeight");}
        if(row.getIntervalDays()==null){row.setIntervalDays(prior==null?2:(int)ChronoUnit.DAYS.between(prior.getRecordDate(),row.getRecordDate()));markInferred(out,row,"intervalDays");}
        if(row.getUfAmount()==null&&row.getOnWeight()!=null&&row.getOffWeight()!=null){row.setUfAmount(row.getOnWeight().subtract(row.getOffWeight()).setScale(2,java.math.RoundingMode.HALF_UP));markInferred(out,row,"ufAmount");}
    }

    private void markInferred(Preview out,DialysisRecord row,String field){out.inferredFields.computeIfAbsent(row.getRecordDate().toString(),k->new ArrayList<>()).add(field);}

    private void classifyRows(Preview out){
        out.warnings.clear();
        for(DialysisRecord r:out.rows){
            List<String> missing=new ArrayList<>();
            if(r.getLastOffWeight()==null)missing.add("previous post-dialysis weight");
            if(r.getOnWeight()==null)missing.add("pre-dialysis weight");
            if(r.getOffWeight()==null)missing.add("post-dialysis weight");
            if(r.getSystolicBp()==null||r.getDiastolicBp()==null)missing.add("blood pressure");
            boolean explicit=out.explicitIncompleteDates.contains(r.getRecordDate().toString());
            r.setRecordType(explicit||!missing.isEmpty()?"INCOMPLETE":"NORMAL");
            if(explicit)out.warnings.add(r.getRecordDate()+": marked as incomplete; provided values were preserved.");
            else if(!missing.isEmpty())out.warnings.add(r.getRecordDate()+": missing "+String.join(", ",missing));
        }
    }
    @Transactional public Map<String,Object> commit(Long pid,String text){
        scope.requirePatient(pid);patients.selectOne(new QueryWrapper<Patient>().eq("id",pid).last("FOR UPDATE"));
        Preview p=preview(pid,text);if(!p.errors.isEmpty())throw new IllegalArgumentException(String.join("; ",p.errors));int added=0;
        for(DialysisRecord r:p.rows){if(p.existingDates.contains(r.getRecordDate().toString()))continue;r.setTextImport(true);if(!service.saveRecord(r))throw new IllegalStateException("The batch could not be saved and was rolled back.");added++;}
        Map<String,Object>result=new LinkedHashMap<>();result.put("added",added);result.put("skipped",p.existingDates.size());return result;
    }
}
