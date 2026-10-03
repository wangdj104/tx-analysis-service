package org.familyhealthcare.service.careplan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Append-only, server-derived audit snapshots. Business callers own state-transition validation. */
@Service
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
public class CarePlanEventStore {
    private final JdbcTemplate jdbc;private final CarePlanAuthorizationService auth;private final CarePlanProperties properties;
    public CarePlanEventStore(JdbcTemplate jdbc,CarePlanAuthorizationService auth,CarePlanProperties properties){this.jdbc=jdbc;this.auth=auth;this.properties=properties;}
    public long append(long patientId,long planId,Long revisionId,Long actionId,long actorId,String eventType,Map<String,Object>payload){
        properties.requireEnabled();CarePlanData.mutationTransaction(jdbc);auth.requireRead(actorId,patientId);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM doctor_care_plan WHERE id=? AND patient_id=? AND workflow_version=1",Integer.class,planId,patientId)!=1)throw CarePlanException.denied();
        if(revisionId!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_revision WHERE id=? AND plan_id=?",Integer.class,revisionId,planId)!=1)throw CarePlanException.denied();
        if(actionId!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_action WHERE id=? AND plan_id=? AND patient_id=? AND revision_id=?",Integer.class,actionId,planId,patientId,revisionId)!=1)throw CarePlanException.denied();
        if(revisionId!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_revision WHERE id=? AND status='DRAFT'",Integer.class,revisionId)>0)auth.requireClinical(actorId,patientId);
        final String type;
        try{type=CarePlanContracts.requireText(eventType,"eventType",40);}catch(IllegalArgumentException invalid){throw CarePlanException.invalid(invalid.getMessage());}
        if(type.startsWith("DRAFT_")||Arrays.asList("REVISION_CREATED","PLAN_PUBLISHED","REVISION_PUBLISHED","PLAN_CANCELLED","PLAN_CLOSED").contains(type))auth.requireClinical(actorId,patientId);
        if(!type.matches("[A-Z][A-Z_]*"))throw CarePlanException.invalid("无效的事件类型。");
        if(payload==null)throw CarePlanException.invalid("事件内容不能为空。");
        for(String field:payload.keySet())if(field.startsWith("actor"))throw CarePlanException.invalid("操作者身份由服务端确定。");
        Map<String,Object>identity=auth.actorSnapshot(actorId,patientId);
        boolean actionEvent=Arrays.asList("RECEIPT_SUBMITTED","HELP_REQUESTED","FOLLOW_UP_RECORDED","RECEIPT_CONFIRMED","RECEIPT_RETURNED").contains(type);
        if(actionEvent){
            if(actionId==null||revisionId==null)throw CarePlanException.invalid("行动事件必须关联行动项和版本。");
            if(Arrays.asList("RECEIPT_CONFIRMED","RECEIPT_RETURNED").contains(type))auth.requireClinical(actorId,patientId);
            else if("FOLLOW_UP_RECORDED".equals(type)){
                try{auth.requireClinical(actorId,patientId);}catch(CarePlanException denied){if(denied.getStatus()!=403)throw denied;auth.requireNursing(actorId,patientId);}
            }else auth.requireRecord(actorId,patientId);
        }
        String note;Instant occurred;String mode;
        try{
            int maximum=Arrays.asList("HELP_REQUESTED","FOLLOW_UP_RECORDED","RECEIPT_RETURNED").contains(type)?1000:2000;
            boolean required=Arrays.asList("RECEIPT_SUBMITTED","HELP_REQUESTED","FOLLOW_UP_RECORDED","RECEIPT_RETURNED").contains(type);
            note=payload.get("note")==null&&!required?null:CarePlanContracts.requireText(payload.get("note"),"note",maximum);
            if(payload.get("occurredAt")!=null&&!(payload.get("occurredAt") instanceof String))throw CarePlanException.invalid("occurredAt 必须为文本。");
            occurred=payload.get("occurredAt")==null?null:CarePlanContracts.parseOffsetInstant((String)payload.get("occurredAt"));
            if(payload.get("entryMode")!=null&&!(payload.get("entryMode") instanceof String))throw CarePlanException.invalid("entryMode 必须为文本。");
            mode=payload.get("entryMode")==null?null:(String)payload.get("entryMode");
            if(mode!=null&&!Arrays.asList("SELF","ASSISTED").contains(mode))throw CarePlanException.invalid("无效的记录方式。");
            if("RECEIPT_SUBMITTED".equals(type)){
                CarePlanContracts.validateReceipt(CarePlanData.map("note",note,"occurredAt",payload.get("occurredAt"),"entryMode",mode,"evidence",payload.get("evidence")));
                if(occurred.isAfter(properties.now()))throw CarePlanException.invalid("实际执行时间不能晚于当前时间。");
                if("SELF".equals(mode)&&jdbc.queryForObject("SELECT COUNT(*) FROM patient WHERE id=? AND user_id=?",Integer.class,patientId,actorId)!=1)throw CarePlanException.denied();
            }else{
                if(actionEvent&&(mode!=null||occurred!=null||!CarePlanData.evidence(payload).isEmpty()))throw CarePlanException.invalid("管理事件不接受执行回执字段。");
                if("FOLLOW_UP_RECORDED".equals(type)&&!Arrays.asList("CONTACTED","AWAITING_INFORMATION","DOCTOR_NOTIFIED").contains(payload.get("kind")))throw CarePlanException.invalid("不支持此跟进类型。");
                // Existing lifecycle/draft callers retain the common evidence identity contract.
                CarePlanContracts.validateReceipt(CarePlanData.map("note",note==null?"Audit":note,"occurredAt",occurred==null?properties.now().toString():occurred.toString(),"entryMode",mode==null?"ASSISTED":mode,"evidence",payload.get("evidence")));
            }
            for(Map<String,Object>ref:CarePlanData.evidence(payload))if(!auth.canReadEvidence(actorId,patientId,(String)ref.get("sourceType"),CarePlanContracts.requireId(ref.get("sourceId"),"sourceId")))throw CarePlanException.denied();
        }catch(IllegalArgumentException invalid){throw CarePlanException.invalid(invalid.getMessage());}
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("INSERT INTO care_plan_event(patient_id,plan_id,revision_id,action_id,actor_id,actor_name,actor_role,actor_relation,entry_mode,event_type,note,occurred_at,recorded_at,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            ps.setLong(1,patientId);ps.setLong(2,planId);nullable(ps,3,revisionId);nullable(ps,4,actionId);ps.setLong(5,actorId);ps.setString(6,(String)identity.get("actorName"));ps.setString(7,(String)identity.get("actorRole"));ps.setString(8,(String)identity.get("actorRelation"));ps.setString(9,mode);ps.setString(10,type);ps.setString(11,note);CarePlanData.time(ps,12,occurred);CarePlanData.time(ps,13,properties.now());ps.setString(14,CarePlanData.json(payload));return ps;},key);
        long eventId=key.getKey().longValue();
        for(Map<String,Object>ref:CarePlanData.evidence(payload))jdbc.update("INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(?,?,?)",eventId,ref.get("sourceType"),CarePlanContracts.requireId(ref.get("sourceId"),"sourceId"));
        return eventId;
    }
    private static void nullable(PreparedStatement ps,int index,Long value)throws SQLException{if(value==null)ps.setNull(index,Types.BIGINT);else ps.setLong(index,value);}
}
