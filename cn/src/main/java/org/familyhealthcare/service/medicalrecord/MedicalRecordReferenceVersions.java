package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import java.sql.PreparedStatement;
import java.util.*;

/** 三种封闭原生锚点。只在原生锚点新建的同一事务绑定，不提供补绑或改绑入口。 */
@Component
public class MedicalRecordReferenceVersions {
    public enum Kind { CARE_PLAN_EVIDENCE, ALERT_EVIDENCE, LEGACY_ALERT }
    private final JdbcTemplate jdbc;private final MedicalRecordValidityProjection validity;
    public MedicalRecordReferenceVersions(JdbcTemplate jdbc,MedicalRecordValidityProjection validity){this.jdbc=jdbc;this.validity=validity;}
    public static final class Anchor {
        public final long id,patient,record;public final Long item;
        public Anchor(long id,long patient,long record,Long item){this.id=id;this.patient=patient;this.record=record;this.item=item;}
    }
    public Map<Long,String> statuses(Kind kind,List<Anchor> anchors,MedicalRecordValidityProjection.Scope scope) {
        if(anchors.size()>100)throw limit();if(anchors.isEmpty())return Collections.emptyMap();
        Set<Long> sources=new LinkedHashSet<>();for(Anchor anchor:anchors)sources.add(anchor.record);
        return projectStatuses(kind,anchors,scope,scope.batch(sources));
    }
    /** 同组只读投影复用已批量读取的来源状态；执行和绑定仍走自行重读的入口。 */
    public Map<Long,String> projectStatuses(Kind kind,List<Anchor> anchors,MedicalRecordValidityProjection.Scope scope,Map<Long,MedicalRecordValidityProjection.State> states) {
        if(anchors.size()>100)throw limit();if(anchors.isEmpty())return Collections.emptyMap();
        Set<Long> ids=new LinkedHashSet<>();for(Anchor anchor:anchors)ids.add(anchor.id);
        String sql=anchorSql(kind)+" WHERE v.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";
        Map<Long,Map<String,Object>> actual=new HashMap<>();for(Map<String,Object> row:jdbc.query(sql,(r,n)->WorkflowData.map("id",r.getLong("id"),"patient",WorkflowData.nullableId(r,"patient_id"),"record",WorkflowData.nullableId(r,"record_id"),"item",WorkflowData.nullableId(r,"item_id")),ids.toArray()))actual.put((Long)row.get("id"),row);
        Map<Long,Map<String,Object>> bindings=new HashMap<>();
        if(scope.ready()) {
            List<Object> args=new ArrayList<>();args.add(kind.name());args.addAll(ids);
            for(Map<String,Object> row:jdbc.query("SELECT reference_id,patient_id,record_id,source_item_id,content_version FROM medical_record_reference_version WHERE reference_kind=? AND reference_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",(r,n)->WorkflowData.map("id",r.getLong("reference_id"),"patient",r.getLong("patient_id"),"record",r.getLong("record_id"),"item",WorkflowData.nullableId(r,"source_item_id"),"version",r.getLong("content_version")),args.toArray()))bindings.put((Long)row.get("id"),row);
        }
        Map<Long,String> result=new LinkedHashMap<>();for(Anchor anchor:anchors){Map<String,Object> nativeRow=actual.get(anchor.id),binding=bindings.get(anchor.id);MedicalRecordValidityProjection.State state=states.get(anchor.record);String status="SOURCE_UNAVAILABLE";
            if(state!=null&&Objects.equals(state.patientId,anchor.patient)&&matches(nativeRow,anchor)) {
                if(!state.managed)status="UNTRACKED";
                else if(!state.eligible())status=state.status();
                else if(binding==null)status="UNKNOWN_NEEDS_RECHECK";
                else if(!matches(binding,anchor))status="SOURCE_UNAVAILABLE";
                else status=Objects.equals(state.version,binding.get("version"))?"CURRENT":"SOURCE_CHANGED";
            }
            result.put(anchor.id,status);
        }return result;
    }
    /** 与状态一起返回实际锚点/绑定的不可变签名，结束核验不再重复逐项读取。 */
    public Map<Long,ReferenceSnapshot> projectSnapshot(Kind kind,List<Anchor> anchors,MedicalRecordValidityProjection.Scope scope,Map<Long,MedicalRecordValidityProjection.State> states) {
        if(anchors.size()>100)throw limit();if(anchors.isEmpty())return Collections.emptyMap();
        Set<Long> ids=new LinkedHashSet<>();for(Anchor anchor:anchors)ids.add(anchor.id);
        String sql=anchorSql(kind)+" WHERE v.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";
        Map<Long,Map<String,Object>> actual=new HashMap<>();for(Map<String,Object> row:snapshotRows(sql,ids,(r,n)->WorkflowData.map("id",r.getLong("id"),"patient",WorkflowData.nullableId(r,"patient_id"),"record",WorkflowData.nullableId(r,"record_id"),"item",WorkflowData.nullableId(r,"item_id"))))actual.put((Long)row.get("id"),row);
        Map<Long,Map<String,Object>> bindings=new HashMap<>();
        if(scope.ready()) {
            List<Object> args=new ArrayList<>();args.add(kind.name());args.addAll(ids);
            for(Map<String,Object> row:snapshotRows("SELECT id,reference_kind,reference_id,patient_id,record_id,source_item_id,content_version,bound_at FROM medical_record_reference_version WHERE reference_kind=? AND reference_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args,(r,n)->WorkflowData.map("bindingId",r.getLong("id"),"kind",r.getString("reference_kind"),"boundAt",r.getTimestamp("bound_at",WorkflowData.utc()).toInstant().toString(),"id",r.getLong("reference_id"),"patient",r.getLong("patient_id"),"record",r.getLong("record_id"),"item",WorkflowData.nullableId(r,"source_item_id"),"version",r.getLong("content_version"))))bindings.put((Long)row.get("id"),row);
        }
        Map<Long,ReferenceSnapshot> result=new LinkedHashMap<>();for(Anchor anchor:anchors){Map<String,Object> nativeRow=actual.get(anchor.id),binding=bindings.get(anchor.id);MedicalRecordValidityProjection.State state=states.get(anchor.record);String status="SOURCE_UNAVAILABLE";
            if(state!=null&&Objects.equals(state.patientId,anchor.patient)&&matches(nativeRow,anchor)) {
                if(!state.managed)status="UNTRACKED";
                else if(!state.eligible())status=state.status();
                else if(binding==null)status="UNKNOWN_NEEDS_RECHECK";
                else if(!matches(binding,anchor))status="SOURCE_UNAVAILABLE";
                else status=Objects.equals(state.version,binding.get("version"))?"CURRENT":"SOURCE_CHANGED";
            }
            result.put(anchor.id,new ReferenceSnapshot(status,WorkflowData.json(WorkflowData.map("anchor",nativeRow,"binding",binding))));
        }return result;
    }
    public static final class ReferenceSnapshot {
        public final String status,signature;
        private ReferenceSnapshot(String status,String signature){this.status=status;this.signature=signature;}
    }
    private <T> List<T> snapshotRows(String sql,Collection<?> args,org.springframework.jdbc.core.RowMapper<T> mapper) {
        return jdbc.query(sql,statement->{statement.setQueryTimeout(5);int index=1;for(Object value:args)statement.setObject(index++,value);},mapper);
    }
    private boolean matches(Map<String,Object> row,Anchor anchor){return row!=null&&Objects.equals(row.get("patient"),anchor.patient)&&Objects.equals(row.get("record"),anchor.record)&&Objects.equals(row.get("item"),anchor.item);}
    private String anchorSql(Kind kind) {
        switch(kind) {
            case CARE_PLAN_EVIDENCE:return "SELECT v.id,p.patient_id,v.source_id AS record_id,NULL AS item_id FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id JOIN doctor_care_plan p ON p.id=e.plan_id AND p.patient_id=e.patient_id JOIN care_plan_action a ON a.id=e.action_id AND a.plan_id=p.id AND a.patient_id=p.patient_id AND v.source_type='MEDICAL_RECORD'";
            case ALERT_EVIDENCE:return "SELECT v.id,a.patient_id,v.source_id AS record_id,NULL AS item_id FROM alert_evidence v JOIN alert_event e ON e.id=v.event_id JOIN alert_record a ON a.id=e.alert_id AND a.patient_id=e.patient_id AND a.workflow_version=1 AND v.source_type='MEDICAL_RECORD'";
            case LEGACY_ALERT:return "SELECT v.id,v.patient_id,i.record_id,i.id AS item_id FROM alert_record v JOIN medical_record_item i ON i.id=v.source_id AND v.source_type='MEDICAL_RECORD_ITEM' AND (v.alert_type IS NULL OR v.alert_type<>'MEASUREMENT_FOLLOW_UP')";
            default:throw new IllegalArgumentException("不支持的引用类型。");
        }
    }
    public long insertCareEvidence(long event,long patient,String type,long source,MedicalRecordValidityProjection.Scope scope) {
        WorkflowData.mutationTransaction(jdbc);MedicalRecordValidityProjection.State state="MEDICAL_RECORD".equals(type)?requireEligible(scope,patient,source):null;
        long id=insert("INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(?,?,?)",event,type,source);
        if(state!=null&&state.managed)bind(Kind.CARE_PLAN_EVIDENCE,new Anchor(id,patient,source,null),state,scope);return id;
    }
    public long insertAlertEvidence(long event,long patient,String type,long source,Long revision,Long version,String dependency,MedicalRecordValidityProjection.Scope scope) {
        WorkflowData.mutationTransaction(jdbc);MedicalRecordValidityProjection.State state="MEDICAL_RECORD".equals(type)?requireEligible(scope,patient,source):null;
        long id=insert("INSERT INTO alert_evidence(event_id,source_type,source_id,source_revision,dependency_version,dependency_state) VALUES(?,?,?,?,?,?)",event,type,source,revision,version,dependency);
        if(state!=null&&state.managed)bind(Kind.ALERT_EVIDENCE,new Anchor(id,patient,source,null),state,scope);return id;
    }
    /** 仅同包阈值写入器在刚插入真实告警后调用。 */
    void bindNewLegacy(long alert,long patient,long source,long item,MedicalRecordValidityProjection.State state,MedicalRecordValidityProjection.Scope scope){bind(Kind.LEGACY_ALERT,new Anchor(alert,patient,source,item),state,scope);}
    private void bind(Kind kind,Anchor anchor,MedicalRecordValidityProjection.State state,MedicalRecordValidityProjection.Scope scope) {
        WorkflowData.mutationTransaction(jdbc);if(!state.managed||!state.eligible()||state.id!=anchor.record||!Objects.equals(state.patientId,anchor.patient)||!scope.ready())throw changed();
        if((kind==Kind.LEGACY_ALERT)!=(anchor.item!=null))throw changed();
        List<Map<String,Object>> rows=jdbc.query(anchorSql(kind)+" WHERE v.id=?",(r,n)->WorkflowData.map("patient",WorkflowData.nullableId(r,"patient_id"),"record",WorkflowData.nullableId(r,"record_id"),"item",WorkflowData.nullableId(r,"item_id")),anchor.id);
        if(rows.size()!=1||!matches(rows.get(0),anchor))throw changed();
        jdbc.update("INSERT INTO medical_record_reference_version(reference_kind,reference_id,patient_id,record_id,source_item_id,content_version,bound_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6))",kind.name(),anchor.id,anchor.patient,anchor.record,anchor.item,state.version);
    }
    public MedicalRecordValidityProjection.State requireEligible(MedicalRecordValidityProjection.Scope scope,long patient,long source){MedicalRecordValidityProjection.State state=scope.one(source);if(state==null||!Objects.equals(state.patientId,patient)||!state.eligible())throw changed();return state;}
    public void requireCurrent(Kind kind,List<Anchor> anchors,MedicalRecordValidityProjection.Scope scope){for(String status:statuses(kind,anchors,scope).values())if(!fresh(status))throw changed();}
    public static boolean fresh(String status){return "CURRENT".equals(status)||"UNTRACKED".equals(status);}
    public static WorkflowException changed(){return new WorkflowException(409,"REFERENCE_CHANGED","使用证据前请重新核对当前来源。");}
    public static WorkflowException limit(){return new WorkflowException(409,"REFERENCE_CHECK_LIMIT","证据引用过多，请人工核对。");}
    private long insert(String sql,Object...args){GeneratedKeyHolder key=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);return key.getKey().longValue();}
}
