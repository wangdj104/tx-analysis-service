package org.familyhealthcare.service.medicalrecord;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.NotificationAudienceService;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        MedicalRecordValidityProjection.Scope scope=validity.open();MedicalRecordValidityProjection.State state=scope.one(record);
        if(state==null||!Objects.equals(state.patientId,patient)||!state.eligible())return null;
        MedicalRecordItem item=items.selectOne(new QueryWrapper<MedicalRecordItem>().eq("id",itemId).eq("record_id",record).last("FOR UPDATE"));
        if(item==null)return null;
        AlertRule rule=rules.selectOne(new QueryWrapper<AlertRule>().eq("id",ruleId).eq("patient_id",patient).last("FOR UPDATE"));
        if(rule==null||!Integer.valueOf(1).equals(rule.getEnabled())||rule.getIndicatorCode()==null)return null;
        List<HealthIndicator> found=indicators.selectList(new QueryWrapper<HealthIndicator>().eq("item_code",rule.getIndicatorCode()).orderByAsc("id").last("LIMIT 2 FOR UPDATE"));
        HealthIndicator indicator=found.size()==1&&rule.getIndicatorCode().equals(found.get(0).getItemCode())?found.get(0):null;
        if(!matches(item,rule,indicator))return null;
        BigDecimal number=state.managed?MedicalRecordValidityProjection.exactNumeric(item.getResultValue()):legacyNumeric(item.getResultValue());
        if(number==null||state.managed&&(indicator==null||!MedicalRecordValidityProjection.exactUnit(item.getUnit(),indicator.getUnit()))||!breached(number,rule.getThresholdType(),rule.getThresholdValue()))return null;
        String dedupe="RULE:"+ruleId+":ITEM:"+itemId;
        AlertRecord active=alerts.selectOne(new QueryWrapper<AlertRecord>().apply(LEGACY).eq("patient_id",patient).eq("dedupe_key",dedupe).in("status",ACTIVE).orderByDesc("id").last("LIMIT 1 FOR UPDATE"));
        if(active!=null) {
            if(!Objects.equals(active.getSourceId(),itemId)||!"MEDICAL_RECORD_ITEM".equals(active.getSourceType())||!Objects.equals(active.getRuleId(),ruleId))return null;
            if(state.managed) {
                String status=references.statuses(MedicalRecordReferenceVersions.Kind.LEGACY_ALERT,Collections.singletonList(new MedicalRecordReferenceVersions.Anchor(active.getId(),patient,record,itemId)),scope).get(active.getId());
                if(!"CURRENT".equals(status))return null;
            } else active.setTriggeredValue(display(item));
            // 已纳管告警的原始触发事实不变，仍按原合同记录重复扫描次数。
            active.setLastTriggeredAt(LocalDateTime.now());active.setOccurrenceCount((active.getOccurrenceCount()==null?1:active.getOccurrenceCount())+1);alerts.updateById(active);return null;
        }
        AlertRecord alert=new AlertRecord();alert.setRuleId(ruleId);alert.setPatientId(patient);alert.setUserId(owner);alert.setAlertType("INDICATOR");alert.setAlertLevel(rule.getAlertLevel());alert.setAlertTitle(rule.getIndicatorName()+" 健康告警");alert.setTriggeredValue(display(item));alert.setTriggeredAt(LocalDateTime.now());alert.setLastTriggeredAt(alert.getTriggeredAt());alert.setSourceType("MEDICAL_RECORD_ITEM");alert.setSourceId(itemId);alert.setDedupeKey(dedupe);alert.setOccurrenceCount(1);alert.setStatus("PENDING");alerts.insert(alert);
        if(state.managed)references.bindNewLegacy(alert.getId(),patient,record,itemId,state,scope);
        return Arrays.asList("WARNING","CRITICAL").contains(rule.getAlertLevel())?new Candidate(alert.getId(),patient,owner,record,itemId,ruleId,state,item.getResultValue(),item.getUnit(),alert.getAlertTitle()):null;
    }
    /** 调用者只能在 evaluate 的真实代理成功返回后调用。投递前只读复核，远端调用不持有来源锁。 */
    public void dispatch(Candidate candidate) {
        if(candidate==null)return;
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("通知投递必须在来源事务之外执行。");
        if(!current(candidate))return;
        audience.notifyCareTeam(candidate.patient,"INDICATOR_ALERT",candidate.title,"新的检查结果触及已配置的阈值，请在关注中心核对；临床处置仍需确认。");
    }
    public boolean current(Candidate c) {
        MedicalRecordValidityProjection.Scope scope=validity.open();MedicalRecordValidityProjection.State state=scope.one(c.record);
        if(state==null||!Objects.equals(state.patientId,c.patient)||!Objects.equals(state.ownerId,c.sourceOwner)||!Objects.equals(state.version,c.version)||state.managed!=c.managed)return false;
        MedicalRecordItem item=items.selectById(c.item);AlertRecord alert=alerts.selectById(c.alert);AlertRule rule=rules.selectById(c.rule);
        if(item==null||!Objects.equals(item.getRecordId(),c.record)||!Objects.equals(item.getResultValue(),c.value)||!Objects.equals(item.getUnit(),c.unit)||alert==null||!Objects.equals(alert.getPatientId(),c.patient)||!Objects.equals(alert.getUserId(),c.owner)||!Objects.equals(alert.getSourceId(),c.item)||!"MEDICAL_RECORD_ITEM".equals(alert.getSourceType())||!Objects.equals(alert.getRuleId(),c.rule)||!ACTIVE.contains(alert.getStatus())||rule==null||!Objects.equals(rule.getPatientId(),c.patient)||!Integer.valueOf(1).equals(rule.getEnabled())||!Arrays.asList("WARNING","CRITICAL").contains(rule.getAlertLevel()))return false;
        if(c.managed&&(!state.eligible()||!"CURRENT".equals(references.statuses(MedicalRecordReferenceVersions.Kind.LEGACY_ALERT,Collections.singletonList(new MedicalRecordReferenceVersions.Anchor(c.alert,c.patient,c.record,c.item)),scope).get(c.alert))))return false;
        if(rule.getIndicatorCode()==null)return false;
        List<HealthIndicator> found=indicators.selectList(new QueryWrapper<HealthIndicator>().eq("item_code",rule.getIndicatorCode()).last("LIMIT 2"));
        HealthIndicator indicator=found.size()==1&&rule.getIndicatorCode().equals(found.get(0).getItemCode())?found.get(0):null;
        // 未纳管仍按原解析和规则匹配复核，不附加核对或单位门槛。
        BigDecimal value=c.managed?MedicalRecordValidityProjection.exactNumeric(item.getResultValue()):legacyNumeric(item.getResultValue());
        if(!matches(item,rule,indicator)||value==null||!breached(value,rule.getThresholdType(),rule.getThresholdValue()))return false;
        if(c.managed&&(indicator==null||!MedicalRecordValidityProjection.exactUnit(item.getUnit(),indicator.getUnit())))return false;
        // 多次只读查询之间若发生首次纳管或再次更正，也不能沿用最早的分类。
        MedicalRecordValidityProjection.State finalState=scope.one(c.record);
        return finalState!=null&&Objects.equals(finalState.patientId,state.patientId)&&Objects.equals(finalState.ownerId,state.ownerId)&&Objects.equals(finalState.version,state.version)&&finalState.managed==state.managed&&Objects.equals(finalState.validity,state.validity)&&Objects.equals(finalState.review,state.review);
    }
    public static final class Candidate {
        final long alert,patient,owner,record,item,rule;final Long version,sourceOwner;final boolean managed;final String value,unit,title;
        private Candidate(long alert,long patient,long owner,long record,long item,long rule,MedicalRecordValidityProjection.State state,String value,String unit,String title){this.alert=alert;this.patient=patient;this.owner=owner;this.record=record;this.item=item;this.rule=rule;this.version=state.version;this.sourceOwner=state.ownerId;this.managed=state.managed;this.value=value;this.unit=unit;this.title=title;}
    }
    private static boolean matches(MedicalRecordItem item,AlertRule rule,HealthIndicator indicator){if(rule.getIndicatorCode().equals(item.getItemCode()))return true;if(indicator==null)return false;if(Objects.equals(indicator.getItemName(),item.getItemName()))return true;if(indicator.getAliases()!=null)for(String alias:indicator.getAliases().split(","))if(!alias.trim().isEmpty()&&alias.trim().equals(item.getItemName()))return true;return false;}
    private static String display(MedicalRecordItem item){return item.getResultValue()+(item.getUnit()!=null?" "+item.getUnit():"");}
    private static final Pattern LEGACY_NUMBER=Pattern.compile("(-?\\d+\\.?\\d*)");
    static BigDecimal legacyNumeric(String value){if(value==null)return null;try{return new BigDecimal(value.trim());}catch(NumberFormatException bad){Matcher match=LEGACY_NUMBER.matcher(value.trim());if(match.find())try{return new BigDecimal(match.group(1));}catch(NumberFormatException ignored){}return null;}}
    static boolean breached(BigDecimal value,String type,String threshold){try{switch(type){case "ABOVE":return value.compareTo(new BigDecimal(threshold.trim()))>0;case "BELOW":return value.compareTo(new BigDecimal(threshold.trim()))<0;case "OUT_OF_RANGE":String[] range=threshold.trim().split("-");return range.length==2&&(value.compareTo(new BigDecimal(range[0]))<0||value.compareTo(new BigDecimal(range[1]))>0);default:return false;}}catch(RuntimeException invalid){return false;}}
}
