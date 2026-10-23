package org.familyhealthcare.service;

import org.familyhealthcare.entity.HealthEvent;
import org.familyhealthcare.service.alertworkflow.*;
import org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService;
import org.familyhealthcare.service.emergencyassistance.EmergencyAssistanceAuthorizationService;
import org.familyhealthcare.service.medicationexecution.MedicationExecutionAuthorizationService;
import org.familyhealthcare.service.workflow.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.util.ExportLocalization.text;

/** Read source facts, never notification status or copied clinical/evidence payloads. */
@Service
public class HealthTimelineFacts {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HouseholdWorkflowSchema schema;
    @Autowired private MedicationExecutionAuthorizationService medication;
    @Autowired private FamilyAssistanceAuthorizationService family;
    @Autowired private EmergencyAssistanceAuthorizationService emergency;
    @Autowired private AlertWorkflowAuthorizationService alerts;
    @Autowired private AlertWorkflowProperties alertProperties;

    public List<HealthEvent> list(long actor,long patient,LocalDate from,LocalDate to,int limit,List<Runnable> rechecks) {
        List<HealthEvent> out=new ArrayList<>();
        boolean med=medication.canRead(actor,patient),ordinary=family.canRead(actor,patient,"HANDOVER"),appointment=family.canRead(actor,patient,"ACCOMPANIMENT"),urgent=emergency.canRead(actor,patient);
        if(med||ordinary||appointment||urgent){
            boolean modern=modernStorage();
            if(med){
                rechecks.add(()->medication.requireRead(actor,patient));
                if(modern){
                    out.addAll(events(patient,from,to,limit,"MEDICATION_EXECUTION_EVENT",text("Medication follow-up","用药跟进"),
                        "medication_execution_event e JOIN medication_execution p ON p.id=e.execution_id AND p.patient_id=e.patient_id JOIN medication m ON m.id=p.medication_id AND m.patient_id=p.patient_id AND m.execution_id=p.id AND m.workflow_version=1 LEFT JOIN medication_reconciliation r ON r.id=COALESCE(p.pending_reconciliation_id,p.current_reconciliation_id) AND r.execution_id=p.id AND r.patient_id=p.patient_id",
                        "e.execution_id","e.type","e.occurred_at","r.state",null));
                    out.addAll(events(patient,from,to,limit,"STOCK_EVENT",text("Inventory follow-up","库存跟进"),
                        "stock_event e JOIN medication_stock p ON p.id=e.stock_id AND p.patient_id=e.patient_id JOIN medication m ON m.id=p.medication_id AND m.patient_id=p.patient_id JOIN stock_work w ON w.id=e.work_id AND w.stock_id=p.id AND w.patient_id=e.patient_id LEFT JOIN stock_work current_work ON current_work.id=p.current_work_id AND current_work.stock_id=p.id AND current_work.patient_id=p.patient_id",
                        "e.stock_id","e.type","e.occurred_at","current_work.state",null));
                }
                // Actual movements remain separate facts from follow-up closure and reminder delivery.
                out.addAll(events(patient,from,to,limit,"STOCK_MOVEMENT",text("Inventory movement recorded","出入库记录"),
                    "medication_stock_movement e JOIN medication m ON m.id=e.medication_id AND m.patient_id=e.patient_id JOIN medication_stock p ON p.medication_id=e.medication_id AND p.patient_id=e.patient_id",
                    "p.id","CASE WHEN e.quantity>0 THEN 'STOCK_IN' WHEN e.quantity<0 THEN 'STOCK_OUT' ELSE 'STOCK_ADJUSTED' END","e.created_at","NULL",null,false));
            }
            if(modern&&(ordinary||appointment)){
                String kinds=ordinary&&appointment?"":ordinary?" AND p.request_kind<>'ACCOMPANIMENT'":" AND p.request_kind='ACCOMPANIMENT'";
                out.addAll(events(patient,from,to,limit,"FAMILY_ASSISTANCE_EVENT",text("Family collaboration","家庭协作"),
                    "family_assistance_event e JOIN family_assistance p ON p.id=e.assistance_id AND p.patient_id=e.patient_id LEFT JOIN care_item c ON c.id=p.care_item_id AND c.patient_id=p.patient_id LEFT JOIN care_appointment a ON a.id=p.care_appointment_id AND a.patient_id=p.patient_id",
                    "e.assistance_id","e.type","e.occurred_at","p.state"," AND (c.id IS NOT NULL OR a.id IS NOT NULL)"+kinds));
                if(ordinary)rechecks.add(()->family.requireRead(actor,patient,"HANDOVER"));
                if(appointment)rechecks.add(()->family.requireRead(actor,patient,"ACCOMPANIMENT"));
            }
            if(urgent){
                rechecks.add(()->emergency.requireRead(actor,patient));
                if(modern)out.addAll(events(patient,from,to,limit,"EMERGENCY_ASSISTANCE_EVENT",text("Emergency assistance","紧急求助"),
                    "emergency_assistance_event e JOIN emergency_event p ON p.id=e.emergency_event_id AND p.patient_id=e.patient_id AND p.workflow_version=1",
                    "e.emergency_event_id","e.type","e.occurred_at","p.workflow_state",null));
                out.addAll(events(patient,from,to,limit,"EMERGENCY_RECORD",text("Emergency request recorded","紧急求助记录"),
                    "emergency_event e","e.id","'EMERGENCY_REQUESTED'","e.triggered_at","e.status",modern?" AND e.workflow_version=0":null,false));
            }
        }
        if(alertProperties.isEnabled()){
            try{alerts.requireRead(actor,patient);}catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;return out;}
            rechecks.add(()->alerts.requireRead(actor,patient));
            List<HealthEvent> candidates=events(patient,from,to,limit,"ALERT_EVENT",text("Measurement abnormality follow-up","测量异常跟进"),
                "alert_event e JOIN alert_record p ON p.id=e.alert_id AND p.patient_id=e.patient_id AND p.workflow_version=1",
                "e.alert_id","e.event_type","e.recorded_at","p.workflow_state",null);
            Map<Long,Map<String,Object>> sources=new HashMap<>();
            for(HealthEvent event:candidates){
                Map<String,Object> source=sources.computeIfAbsent(event.getSourceRecordId(),id->{List<Map<String,Object>> rows=jdbc.queryForList("SELECT source_type,source_id FROM alert_record WHERE id=? AND patient_id=?",id,patient);return rows.isEmpty()?Collections.emptyMap():rows.get(0);});
                if(!(source.get("source_id") instanceof Number))continue;
                long sourceId=((Number)source.get("source_id")).longValue();String type=(String)source.get("source_type");
                if(!alerts.canReadEvidence(actor,patient,type,sourceId))continue;
                event.setRelatedSourceType("MEASUREMENT".equals(type)?"HEALTH_MEASUREMENT":type);event.setRelatedSourceId(sourceId);out.add(event);
                rechecks.add(()->{if(!alerts.canReadEvidence(actor,patient,type,sourceId))throw AlertWorkflowException.denied();});
            }
        }
        return out;
    }

    private List<HealthEvent> events(long patient,LocalDate from,LocalDate to,int limit,String source,String title,String tables,String target,String type,String time,String state,String extra){return events(patient,from,to,limit,source,title,tables,target,type,time,state,extra,true);}

    private List<HealthEvent> events(long patient,LocalDate from,LocalDate to,int limit,String source,String title,String tables,String target,String type,String time,String state,String extra,boolean utc){
        String sql="SELECT e.id,"+target+" AS target_id,"+type+" AS event_type,"+time+" AS event_at,"+state+" AS current_state FROM "+tables+" WHERE e.patient_id=? AND "+time+" IS NOT NULL"+(extra==null?"":extra);
        List<Object> args=new ArrayList<>();args.add(patient);
        if(from!=null){sql+=" AND "+time+">=?";args.add(from.atStartOfDay());}
        if(to!=null){sql+=" AND "+time+"<?";args.add(to.plusDays(1).atStartOfDay());}
        sql+=" ORDER BY "+time+" DESC,e.id DESC LIMIT ?";args.add(limit);
        return jdbc.query(sql,(rs,index)->{
            LocalDateTime at=rs.getTimestamp("event_at",Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
            HealthEvent event=new HealthEvent();event.setId(rs.getLong("id"));event.setSourceId(event.getId());event.setPatientId(patient);event.setSourceType(source);event.setSourceRecordId(rs.getLong("target_id"));event.setEventType(rs.getString("event_type"));event.setTitle(title);event.setEventDate(at.toLocalDate());event.setEventTime(at.toLocalTime().toString());
            if(utc)event.setOccurredAt(rs.getTimestamp("event_at",Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().toString());
            event.setCurrentState(rs.getString("current_state"));return event;
        },args.toArray());
    }

    public List<HealthEvent> measurements(long patient,LocalDate from,LocalDate to,int limit){
        return events(patient,from,to,limit,"HEALTH_MEASUREMENT",text("Measurement recorded","测量记录"),"health_measurement e","e.id","e.metric_type","e.measured_at","NULL",null,false);
    }
    boolean modernStorage(){
        HouseholdWorkflowSchema.StorageReadiness storage=schema.inspectStorageMetadata();
        if(storage.getStatus()==HouseholdWorkflowSchema.StorageStatus.ABSENT)return false;
        storage.requireStructurallyReady();return true;
    }
}
