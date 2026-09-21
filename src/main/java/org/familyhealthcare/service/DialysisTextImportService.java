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
        Preview out=new Preview();if(text==null||text.trim().isEmpty()){out.errors.add("请至少粘贴一条透析记录。");return out;}
        if(text.length()>100000){out.errors.add("单次导入最多支持 100,000 个字符，请将较大的文件拆分后分批导入。");return out;}
        DialysisRecord current=null;Set<LocalDate>dates=new HashSet<>();Set<String>fields=new HashSet<>();int line=0;
        for(String raw:text.replace("\r","").split("\n")){line++;String value=raw.trim();if(value.isEmpty()||value.startsWith("#"))continue;
            for(String cell:value.split("[;；|]",-1)){cell=cell.trim();if(cell.isEmpty())continue;String[]pair=cell.split("\\s*[:：]\\s*",2);
                if(pair.length!=2){out.errors.add("第 "+line+" 行无法解析，请使用“字段: 值”格式："+cell);continue;}
                String key=normalizeKey(pair[0].trim()),v=pair[1].trim();
                try{
                    if("Date".equals(key)){LocalDate day=LocalDate.parse(v);if(!dates.add(day))throw new IllegalArgumentException("同一日期重复出现。");current=new DialysisRecord();current.setRecordDate(day);out.rows.add(current);fields.clear();continue;}
                    if(current==null)throw new IllegalArgumentException("请先填写日期，再填写记录字段。");
                    if(!fields.add(key))throw new IllegalArgumentException("同一日期存在重复字段："+displayKey(key));
                    if("Notes".equals(key)||"Missing reason".equals(key)){current.setRemark((current.getRemark()==null?"":current.getRemark()+"; ")+v);continue;}
                    if("Status".equals(key)){String status=normalizeStatus(v);if(!Arrays.asList("Normal","Missing","Partially missing","Data missing").contains(status))throw new IllegalArgumentException("状态必须为正常、缺失、部分缺失或数据缺失。");if(!"Normal".equals(status)){current.setRecordType("INCOMPLETE");out.explicitIncompleteDates.add(current.getRecordDate().toString());}continue;}
                    if(!Arrays.asList("Previous post-dialysis weight","Pre-dialysis weight","Post-dialysis weight","Fluid removed","Interval days","Blood pressure").contains(key))throw new IllegalArgumentException("无法识别的字段："+displayKey(key));
                    if(v.isEmpty()||Arrays.asList("missing","unknown","not measured","none","缺失","未知","未测量","无","-","?").contains(v.toLowerCase(java.util.Locale.ROOT)))continue;
                    if("Blood pressure".equals(key)){String[]bp=v.replace("mmHg","").replace("毫米汞柱","").trim().split("[/／]");if(bp.length!=2)throw new IllegalArgumentException("血压请填写为 120/80，或留空。");current.setSystolicBp(positive(bp[0]).intValueExact());current.setDiastolicBp(positive(bp[1]).intValueExact());}
                    else if("Interval days".equals(key))current.setIntervalDays(positive(v.replace("days","").replace("天","").trim()).intValueExact());
                    else {BigDecimal n=new BigDecimal(v.replace("kg","").replace("公斤","").replace("千克","").trim());if(n.signum()<0||(!"Fluid removed".equals(key)&&n.signum()==0))throw new IllegalArgumentException("请输入有效的非负数，体重必须大于零。");switch(key){case "Previous post-dialysis weight":current.setLastOffWeight(n);break;case "Pre-dialysis weight":current.setOnWeight(n);break;case "Post-dialysis weight":current.setOffWeight(n);break;case "Fluid removed":current.setUfAmount(n);break;}}
                }catch(Exception e){out.errors.add("第 "+line+" 行（"+displayKey(key)+"）："+(e instanceof IllegalArgumentException?e.getMessage():"数值格式无效。"));}
            }
        }
        out.rows.sort(Comparator.comparing(DialysisRecord::getRecordDate));
        classifyRows(out);
        if(out.rows.isEmpty())out.errors.add("未识别到包含日期的记录。");if(out.rows.size()>366)out.errors.add("单次最多可导入 366 条记录。");
        return out;
    }
    private String normalizeKey(String key){
        Map<String,String> aliases=new HashMap<>();
        aliases.put("日期","Date");aliases.put("上次透后体重","Previous post-dialysis weight");aliases.put("透前体重","Pre-dialysis weight");aliases.put("本次透前体重","Pre-dialysis weight");aliases.put("透后体重","Post-dialysis weight");aliases.put("本次透后体重","Post-dialysis weight");aliases.put("超滤量","Fluid removed");aliases.put("脱水量","Fluid removed");aliases.put("间隔天数","Interval days");aliases.put("血压","Blood pressure");aliases.put("备注","Notes");aliases.put("状态","Status");aliases.put("缺失原因","Missing reason");
        return aliases.getOrDefault(key,key);
    }
    private String displayKey(String key){
        Map<String,String> labels=new HashMap<>();
        labels.put("Date","日期");labels.put("Previous post-dialysis weight","上次透后体重");labels.put("Pre-dialysis weight","透前体重");labels.put("Post-dialysis weight","透后体重");labels.put("Fluid removed","超滤量");labels.put("Interval days","间隔天数");labels.put("Blood pressure","血压");labels.put("Notes","备注");labels.put("Status","状态");labels.put("Missing reason","缺失原因");
        return labels.getOrDefault(key,key);
    }
    private String normalizeStatus(String value){
        if("正常".equals(value))return "Normal";
        if("缺失".equals(value))return "Missing";
        if("部分缺失".equals(value))return "Partially missing";
        if("数据缺失".equals(value))return "Data missing";
        return value;
    }
    private BigDecimal positive(String s){BigDecimal n=new BigDecimal(s.trim());if(n.signum()<=0)throw new IllegalArgumentException("数值必须大于零。");return n;}
    public Preview preview(Long pid,String text){
        if(scope.requirePatient(pid)==null)throw new IllegalArgumentException("请选择家庭成员。");
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
            if(r.getLastOffWeight()==null)missing.add("上次透后体重");
            if(r.getOnWeight()==null)missing.add("透前体重");
            if(r.getOffWeight()==null)missing.add("透后体重");
            if(r.getSystolicBp()==null||r.getDiastolicBp()==null)missing.add("血压");
            boolean explicit=out.explicitIncompleteDates.contains(r.getRecordDate().toString());
            r.setRecordType(explicit||!missing.isEmpty()?"INCOMPLETE":"NORMAL");
            if(explicit)out.warnings.add(r.getRecordDate()+"：已标记为不完整，已填写的值会被保留。");
            else if(!missing.isEmpty())out.warnings.add(r.getRecordDate()+"：缺少"+String.join("、",missing));
        }
    }
    @Transactional public Map<String,Object> commit(Long pid,String text){
        scope.requirePatient(pid);patients.selectOne(new QueryWrapper<Patient>().eq("id",pid).last("FOR UPDATE"));
        Preview p=preview(pid,text);if(!p.errors.isEmpty())throw new IllegalArgumentException(String.join("; ",p.errors));int added=0;
        for(DialysisRecord r:p.rows){if(p.existingDates.contains(r.getRecordDate().toString()))continue;r.setTextImport(true);if(!service.saveRecord(r))throw new IllegalStateException("批量保存失败，本次导入已回滚。");added++;}
        Map<String,Object>result=new LinkedHashMap<>();result.put("added",added);result.put("skipped",p.existingDates.size());return result;
    }
}
