package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.i18n.LocaleContextHolder;
import lombok.Data;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.text.Normalizer;
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
        Preview out=new Preview();if(text==null||text.trim().isEmpty()){out.errors.add(message("Paste at least one dialysis record.","请至少粘贴一条透析记录。"));return out;}
        if(text.length()>100000){out.errors.add(message("A single import may contain up to 100,000 characters. Split larger imports into batches.","单次导入最多支持 100,000 个字符，请拆分后分批导入。"));return out;}
        DialysisRecord current=null;Set<LocalDate>dates=new HashSet<>();Set<String>fields=new HashSet<>();int line=0;
        for(String raw:text.replace("\r","").replace("\uFEFF","").split("\n")){line++;String value=raw.trim();if(value.isEmpty()||value.startsWith("#"))continue;
            // Preserve punctuation in notes unless it starts another field/value pair.
            for(String cell:value.split("[;；|](?=\\s*[^:：;；|]+[:：])")){cell=cell.trim();if(cell.isEmpty())continue;String[]pair=cell.split("\\s*[:：]\\s*",2);
                if(pair.length!=2){out.errors.add(message("Line "+line+" could not be parsed. Use 'Field: value': ","第 "+line+" 行无法解析，请使用“字段: 值”格式：")+cell);continue;}
                String key=normalizeKey(pair[0]),v=normalize(pair[1]);
                try{
                    // Invalid/duplicate dates must not attach subsequent fields to the previous session.
                    if("Date".equals(key)){current=null;fields.clear();LocalDate day=LocalDate.parse(v.replace('/','-').replace('.','-').replace("年","-").replace("月","-").replace("日",""),DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT));if(!dates.add(day))throw invalid("The same date appears more than once.","同一日期重复出现。");current=new DialysisRecord();current.setRecordDate(day);out.rows.add(current);continue;}
                    if(current==null)throw invalid("Enter a valid date before the record fields.","请先填写有效日期，再填写记录字段。");
                    if(!fields.add(key))throw new IllegalArgumentException(message("Duplicate field for the same date: ","同一日期存在重复字段：")+displayKey(key));
                    if("Notes".equals(key)||"Missing reason".equals(key)){String note=pair[1].trim();if(!note.isEmpty())current.setRemark((current.getRemark()==null?"":current.getRemark()+"; ")+note);continue;}
                    if("Status".equals(key)){String status=normalizeStatus(v);if(status.isEmpty())continue;if(!Arrays.asList("Normal","Missing","Partially missing","Data missing").contains(status))throw invalid("Status must be Normal, Missing, Partially missing, or Data missing.","状态必须为正常、缺失、部分缺失或数据缺失。");if(!"Normal".equals(status)){current.setRecordType("INCOMPLETE");out.explicitIncompleteDates.add(current.getRecordDate().toString());}continue;}
                    if(!Arrays.asList("Previous post-dialysis weight","Pre-dialysis weight","Post-dialysis weight","Fluid removed","Interval days","Blood pressure").contains(key))throw new IllegalArgumentException(message("Unrecognized field: ","无法识别的字段：")+displayKey(key));
                    if(v.isEmpty()||Arrays.asList("missing","unknown","not measured","none","缺失","未知","未测量","未测","无","-","?").contains(v.toLowerCase(java.util.Locale.ROOT)))continue;
                    if("Blood pressure".equals(key)){String[]bp=v.replaceAll("(?i)mmhg|毫米汞柱","").trim().split("/",-1);if(bp.length!=2)throw invalid("Enter blood pressure as 120/80, or leave it blank.","血压请填写为 120/80，或留空。");int systolic=positive(bp[0]).intValueExact(),diastolic=positive(bp[1]).intValueExact();current.setSystolicBp(systolic);current.setDiastolicBp(diastolic);}
                    else if("Interval days".equals(key))current.setIntervalDays(positive(v.replaceAll("(?i)days?|天","").trim()).intValueExact());
                    else {BigDecimal n=new BigDecimal(v.replaceAll("(?i)kg|公斤|千克","").trim());if(n.signum()<0||(!"Fluid removed".equals(key)&&n.signum()==0))throw invalid("Enter a valid non-negative value; weights must be greater than zero.","请输入有效的非负数，体重必须大于零。");switch(key){case "Previous post-dialysis weight":current.setLastOffWeight(n);break;case "Pre-dialysis weight":current.setOnWeight(n);break;case "Post-dialysis weight":current.setOffWeight(n);break;case "Fluid removed":current.setUfAmount(n);break;}}
                }catch(DateTimeParseException e){out.errors.add(lineError(line,key,message("Enter a valid date, for example 2026-09-21.","请输入有效日期，例如 2026-09-21。")));}
                catch(NumberFormatException|ArithmeticException e){out.errors.add(lineError(line,key,message("Invalid numeric value. Blood pressure and interval days must be whole numbers.","数值格式无效；血压和间隔天数必须为整数。")));}
                catch(IllegalArgumentException e){out.errors.add(lineError(line,key,e.getMessage()));}
            }
        }
        out.rows.sort(Comparator.comparing(DialysisRecord::getRecordDate));
        classifyRows(out);
        if(out.rows.isEmpty())out.errors.add(message("No dated records were recognized.","未识别到包含日期的记录。"));if(out.rows.size()>366)out.errors.add(message("A single import may contain up to 366 records.","单次最多可导入 366 条记录。"));
        return out;
    }
    private static final Map<String,String> FIELD_ALIASES=new LinkedHashMap<>();
    private static final Map<String,String> CHINESE_LABELS=new LinkedHashMap<>();
    static {
        field("Date","日期","透析日期");field("Previous post-dialysis weight","上次透后体重","上次透析后体重");
        field("Pre-dialysis weight","透前体重","本次透前体重");field("Post-dialysis weight","透后体重","本次透后体重");
        field("Fluid removed","超滤量","脱水量");field("Interval days","间隔天数");field("Blood pressure","血压");
        field("Notes","备注");field("Status","状态");field("Missing reason","缺失原因");
    }
    private static void field(String key,String chinese,String...aliases){FIELD_ALIASES.put(key.toLowerCase(Locale.ROOT),key);FIELD_ALIASES.put(chinese,key);for(String alias:aliases)FIELD_ALIASES.put(alias,key);CHINESE_LABELS.put(key,chinese);}
    private String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ");}
    private String normalizeKey(String key){String value=normalize(key);return FIELD_ALIASES.getOrDefault(value.toLowerCase(Locale.ROOT),value);}
    private String displayKey(String key){return chinese()?CHINESE_LABELS.getOrDefault(key,key):key;}
    private String normalizeStatus(String value){String[]en={"Normal","Missing","Partially missing","Data missing"},zh={"正常","缺失","部分缺失","数据缺失"};for(int i=0;i<en.length;i++)if(en[i].equalsIgnoreCase(value)||zh[i].equals(value))return en[i];return value;}
    private static boolean chinese(){return "zh".equals(LocaleContextHolder.getLocale().getLanguage());}
    private static String message(String english,String chinese){return chinese()?chinese:english;}
    private IllegalArgumentException invalid(String english,String chinese){return new IllegalArgumentException(message(english,chinese));}
    private String lineError(int line,String key,String error){return message("Line "+line+" ("+displayKey(key)+"): ","第 "+line+" 行（"+displayKey(key)+"）：")+error;}
    private BigDecimal positive(String s){BigDecimal n=new BigDecimal(s.trim());if(n.signum()<=0)throw invalid("The value must be greater than zero.","数值必须大于零。");return n;}
    public Preview preview(Long pid,String text){
        if(pid==null||pid<=0||scope.requirePatient(pid)==null)throw invalid("Select a family member.","请选择家庭成员。");
        Preview out=parse(text);
        List<DialysisRecord> history=records.selectList(new QueryWrapper<DialysisRecord>().eq("patient_id",pid).orderByAsc("record_date").orderByAsc("id"));
        if(history==null)history=new ArrayList<>();
        List<DialysisRecord> previous=new ArrayList<>(history);
        out.getWarnings().clear();
        for(DialysisRecord r:out.rows){
            r.setPatientId(pid);
            boolean existing=records.selectCount(new QueryWrapper<DialysisRecord>().eq("patient_id",pid).eq("record_date",r.getRecordDate()))>0;
            if(existing)out.existingDates.add(r.getRecordDate().toString());
            inferPreviewFields(r,previous,out);
            if(!existing)previous.add(r);
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
        if(row.getIntervalDays()==null&&prior!=null){row.setIntervalDays((int)ChronoUnit.DAYS.between(prior.getRecordDate(),row.getRecordDate()));markInferred(out,row,"intervalDays");}
        if(row.getUfAmount()==null&&row.getOnWeight()!=null&&row.getOffWeight()!=null){BigDecimal removed=row.getOnWeight().subtract(row.getOffWeight()).setScale(2,java.math.RoundingMode.HALF_UP);if(removed.signum()>=0){row.setUfAmount(removed);markInferred(out,row,"ufAmount");}else out.errors.add(row.getRecordDate()+message(": post-dialysis weight exceeds pre-dialysis weight. Verify the weights or enter the actual fluid removed.","：透后体重大于透前体重，请核对体重或填写实际超滤量。"));}
    }

    private void markInferred(Preview out,DialysisRecord row,String field){out.inferredFields.computeIfAbsent(row.getRecordDate().toString(),k->new ArrayList<>()).add(field);}

    private void classifyRows(Preview out){
        out.warnings.clear();
        for(DialysisRecord r:out.rows){
            List<String> missing=new ArrayList<>();
            if(r.getLastOffWeight()==null)missing.add(displayKey("Previous post-dialysis weight"));
            if(r.getOnWeight()==null)missing.add(displayKey("Pre-dialysis weight"));
            if(r.getOffWeight()==null)missing.add(displayKey("Post-dialysis weight"));
            if(r.getSystolicBp()==null||r.getDiastolicBp()==null)missing.add(displayKey("Blood pressure"));
            boolean explicit=out.explicitIncompleteDates.contains(r.getRecordDate().toString());
            r.setRecordType(explicit||!missing.isEmpty()?"INCOMPLETE":"NORMAL");
            if(explicit)out.warnings.add(r.getRecordDate()+message(": marked as incomplete; provided values were preserved.","：已标记为不完整，已填写的值会被保留。"));
            else if(!missing.isEmpty())out.warnings.add(r.getRecordDate()+message(": missing ","：缺少")+String.join(message(", ","、"),missing));
        }
    }
    @Transactional public Map<String,Object> commit(Long pid,String text){
        if(pid==null||pid<=0)throw invalid("Select a family member.","请选择家庭成员。");
        scope.requirePatient(pid);patients.selectOne(new QueryWrapper<Patient>().eq("id",pid).last("FOR UPDATE"));
        Preview p=preview(pid,text);if(!p.errors.isEmpty())throw new IllegalArgumentException(String.join("; ",p.errors));int added=0;
        for(DialysisRecord r:p.rows){if(p.existingDates.contains(r.getRecordDate().toString()))continue;r.setTextImport(true);if(!service.saveRecord(r))throw new IllegalStateException(message("The batch could not be saved and was rolled back.","批量保存失败，本次导入已回滚。"));added++;}
        Map<String,Object>result=new LinkedHashMap<>();result.put("added",added);result.put("skipped",p.existingDates.size());return result;
    }
}
