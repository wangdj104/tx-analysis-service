package org.familyhealthcare.service.medicalrecord;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.NotificationAudienceService;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.*;

/** 每个候选独立经代理提交。原生父锁后重新分类，阻断首次纳管与旧阈值刷新的竞态。 */
@Service
public class MedicalRecordThresholdWriter {
    private final JdbcTemplate jdbc;private final MedicalRecordItemMapper items;private final AlertRuleMapper rules;
    private final HealthIndicatorMapper indicators;private final AlertRecordMapper alerts;
    private final MedicalRecordValidityProjection validity;private final MedicalRecordReferenceVersions references;
    private final NotificationAudienceService audience;
    private static final String LEGACY="(alert_type IS NULL OR alert_type<>'MEASUREMENT_FOLLOW_UP')";
    private static final List<String> ACTIVE=Arrays.asList("PENDING","CONFIRMED","OBSERVING","CONSULTED","RECHECKED");
    public MedicalRecordThresholdWriter(JdbcTemplate jdbc,MedicalRecordItemMapper items,AlertRuleMapper rules,HealthIndicatorMapper indicators,AlertRecordMapper alerts,MedicalRecordValidityProjection validity,MedicalRecordReferenceVersions references,NotificationAudienceService audience){this.jdbc=jdbc;this.items=items;this.rules=rules;this.indicators=indicators;this.alerts=alerts;this.validity=validity;this.references=references;this.audience=audience;}

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Candidate evaluate(long patient,long owner,long record,long itemId,long ruleId) {
        WorkflowData.mutationTransaction(jdbc);
        if(jdbc.queryForList("SELECT id FROM medical_record WHERE id=? AND patient_id=? FOR UPDATE",Long.class,record,patient).isEmpty())return null;
        MedicalRecordValidityProjection.Scope scope=validity.open();
        MedicalRecordReferenceVersions.ThresholdFrame frame=references.prepareThreshold(patient,owner,record,itemId,ruleId,scope);
        if(frame==null)return null;
        try {
            MedicalRecordValidityProjection.State state=frame.source();
            MedicalRecordItem item=items.selectOne(new QueryWrapper<MedicalRecordItem>().eq("id",itemId).eq("record_id",record).last("FOR UPDATE"));
            if(item==null)return null;
            AlertRule rule=rules.selectOne(new QueryWrapper<AlertRule>().eq("id",ruleId).eq("patient_id",patient).last("FOR UPDATE"));
            if(rule==null||!Integer.valueOf(1).equals(rule.getEnabled())||rule.getIndicatorCode()==null)return null;
            List<HealthIndicator> found=indicators.selectList(new QueryWrapper<HealthIndicator>().eq("item_code",rule.getIndicatorCode()).orderByAsc("id").last("LIMIT 2 FOR UPDATE"));
            HealthIndicator indicator=found.size()==1&&rule.getIndicatorCode().equals(found.get(0).getItemCode())?found.get(0):null;
            if(!matches(item,rule,indicator))return null;
            BigDecimal number=state.managed?MedicalRecordValidityProjection.exactNumeric(item.getResultValue()):legacyNumeric(item.getResultValue());
            if(number==null||state.managed&&(indicator==null||!MedicalRecordValidityProjection.exactUnit(item.getUnit(),indicator.getUnit()))||!breached(number,rule.getThresholdType(),rule.getThresholdValue()))return null;
            frame.expectTarget(item,rule,indicator);
            String dedupe="RULE:"+ruleId+":ITEM:"+itemId;
            AlertRecord active=alerts.selectOne(new QueryWrapper<AlertRecord>().apply(LEGACY).eq("patient_id",patient).eq("dedupe_key",dedupe).in("status",ACTIVE).orderByDesc("id").last("LIMIT 1 FOR UPDATE"));
            LocalDateTime time=LocalDateTime.now().withNano(0);
            if(active!=null) {
                if(!Objects.equals(active.getSourceId(),itemId)||!"MEDICAL_RECORD_ITEM".equals(active.getSourceType())||!Objects.equals(active.getRuleId(),ruleId))return null;
                if(frame.refreshExisting(active,display(item),time))frame.complete();
                return null;
            }
            String title=title(rule.getIndicatorName());
            MedicalRecordReferenceVersions.ThresholdInsert inserted=frame.insertNew(rule.getAlertLevel(),title,display(item),time);
            frame.complete();
            return Arrays.asList("WARNING","CRITICAL").contains(rule.getAlertLevel())?new Candidate(inserted,patient,owner,record,itemId,ruleId,state,item.getResultValue(),item.getUnit(),title):null;
        }catch(Throwable failure){throw frame.failure(failure);}
        finally{frame.close();}
    }
    /** 调用者只能在 evaluate 的真实代理成功返回后调用。投递前只读复核，远端调用不持有来源锁。 */
    public void dispatch(Candidate candidate) {
        if(candidate==null)return;
        references.thresholdOutside(candidate);
        if(!current(candidate))return;
        references.thresholdOutside(candidate);
        audience.notifyCareTeam(candidate.patient,"INDICATOR_ALERT",candidate.title,"A new result crossed the configured threshold. Review it in the Attention Center; clinical action still requires confirmation.");
    }
    public boolean current(Candidate candidate) {return references.thresholdCurrent(candidate);}
    public static final class Candidate {
        final MedicalRecordReferenceVersions.ThresholdInsert insert;final long alert,patient,owner,record,item,rule;final Long version,sourceOwner;final boolean managed;final String value,unit,title,generation,sourceRelease;
        private Candidate(MedicalRecordReferenceVersions.ThresholdInsert insert,long patient,long owner,long record,long item,long rule,MedicalRecordValidityProjection.State state,String value,String unit,String title){this.insert=insert;this.alert=insert.id;this.patient=patient;this.owner=owner;this.record=record;this.item=item;this.rule=rule;this.version=state.version;this.sourceOwner=state.ownerId;this.managed=state.managed;this.generation=state.liveGeneration;this.sourceRelease=state.sourceReleaseSignature;this.value=value;this.unit=unit;this.title=title;}
    }
    static String title(String name){return name+" Health Alerts";}
    static boolean matches(MedicalRecordItem item,AlertRule rule,HealthIndicator indicator){if(rule.getIndicatorCode().equals(item.getItemCode()))return true;if(indicator==null)return false;if(Objects.equals(indicator.getItemName(),item.getItemName()))return true;if(indicator.getAliases()!=null)for(String alias:indicator.getAliases().split(","))if(!alias.trim().isEmpty()&&alias.trim().equals(item.getItemName()))return true;return false;}
    private static String display(MedicalRecordItem item){return item.getResultValue()+(item.getUnit()!=null?" "+item.getUnit():"");}
    private static final Pattern LEGACY_NUMBER=Pattern.compile("(-?\\d+\\.?\\d*)");
    static BigDecimal legacyNumeric(String value){if(value==null)return null;try{return new BigDecimal(value.trim());}catch(NumberFormatException bad){Matcher match=LEGACY_NUMBER.matcher(value.trim());if(match.find())try{return new BigDecimal(match.group(1));}catch(NumberFormatException ignored){}return null;}}
    static boolean breached(BigDecimal value,String type,String threshold){try{switch(type){case "ABOVE":return value.compareTo(new BigDecimal(threshold.trim()))>0;case "BELOW":return value.compareTo(new BigDecimal(threshold.trim()))<0;case "OUT_OF_RANGE":String[] range=threshold.trim().split("-");return range.length==2&&(value.compareTo(new BigDecimal(range[0]))<0||value.compareTo(new BigDecimal(range[1]))>0);default:return false;}}catch(RuntimeException invalid){return false;}}
}
