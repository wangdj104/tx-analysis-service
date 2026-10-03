package org.familyhealthcare.service.careplan;

import org.familyhealthcare.entity.HealthEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.careplan.CarePlanData.*;

/** Read-time projection with current access; never writes snapshots to the general timeline. */
@Service
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class CarePlanTimelineProjector {
    private static final List<String> PUBLIC=Arrays.asList("PLAN_PUBLISHED","REVISION_PUBLISHED","RECEIPT_SUBMITTED","HELP_REQUESTED","FOLLOW_UP_RECORDED","RECEIPT_CONFIRMED","RECEIPT_RETURNED","PLAN_CANCELLED","PLAN_CLOSED");
    private static final List<String> PRIVATE=Arrays.asList("DRAFT_CREATED","DRAFT_SAVED","REVISION_CREATED");
    private static final List<String> TIMELINE=Arrays.asList("PLAN_PUBLISHED","REVISION_PUBLISHED","RECEIPT_SUBMITTED","RECEIPT_CONFIRMED","RECEIPT_RETURNED","PLAN_CANCELLED","PLAN_CLOSED");
    private final JdbcTemplate jdbc;private final CarePlanAuthorizationService auth;private final CarePlanProperties properties;private final CarePlanQueryService query;
    public CarePlanTimelineProjector(JdbcTemplate jdbc,CarePlanAuthorizationService auth,CarePlanProperties properties,CarePlanQueryService query){this.jdbc=jdbc;this.auth=auth;this.properties=properties;this.query=query;}

    /** Retains the existing general timeline caller's limit200; the separate event endpoint caps pages at100. */
    public List<HealthEvent> list(long actorId,long patientId,LocalDate from,LocalDate to,int limit){
        if(!properties.isEnabled())return Collections.emptyList();
        if(limit<1||limit>200||from!=null&&to!=null&&from.isAfter(to))throw CarePlanException.invalid("Invalid care-plan timeline range or limit.");
        auth.requireRead(actorId,patientId);
        String sql="SELECT e.id,e.plan_id,e.event_type,e.recorded_at FROM care_plan_event e JOIN doctor_care_plan p ON p.id=e.plan_id AND p.patient_id=e.patient_id JOIN care_plan_revision r ON r.id=e.revision_id AND r.plan_id=e.plan_id WHERE e.patient_id=? AND p.workflow_version=1 AND p.current_revision_id IS NOT NULL AND r.status='PUBLISHED' AND e.event_type IN ("+types(TIMELINE)+")";
        List<Object> args=new ArrayList<>();args.add(patientId);
        if(from!=null){sql+=" AND e.recorded_at>=?";args.add(from.atStartOfDay().toInstant(ZoneOffset.UTC));}
        if(to!=null){sql+=" AND e.recorded_at<?";args.add(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));}
        sql+=" ORDER BY e.recorded_at DESC,e.id DESC LIMIT ?";args.add(limit);
        final String statement=sql;
        List<HealthEvent> events=jdbc.query(connection->{PreparedStatement ps=connection.prepareStatement(statement);bind(ps,args);return ps;},(rs,i)->{
            HealthEvent event=new HealthEvent();long id=rs.getLong("id");String type=rs.getString("event_type");Instant at=Instant.parse(time(rs,"recorded_at"));OffsetDateTime utc=at.atOffset(ZoneOffset.UTC);
            event.setId(id);event.setSourceId(id);event.setSourceType("CARE_PLAN_EVENT");event.setCarePlanId(rs.getLong("plan_id"));event.setPatientId(patientId);
            event.setEventType(type);event.setEventDate(utc.toLocalDate());event.setEventTime(utc.toLocalTime().toString()+"Z");event.setTitle(title(type));event.setSummary("A care-plan update was recorded.");return event;
        });
        auth.requireRead(actorId,patientId);return events;
    }

    /** Minimal EventView page; clinical private events and evidence titles are separately authorized. */
    public Map<String,Object> events(long actorId,long planId,String cursor,int limit){
        properties.requireEnabled();limit(limit);Map<String,Object> plan=query.plan(actorId,planId);long patient=id(plan.get("patientId"));boolean clinical=clinical(actorId,patient);
        String visibility=clinical?"CLINICAL":"PUBLISHED";String[]after=cursor(cursor,actorId,planId,visibility);
        List<String> allowed=new ArrayList<>(PUBLIC);if(clinical)allowed.addAll(PRIVATE);
        String sql="SELECT e.id,e.actor_id,e.actor_name,e.actor_role,e.entry_mode,e.event_type,e.note,e.occurred_at,e.recorded_at FROM care_plan_event e LEFT JOIN care_plan_revision r ON r.id=e.revision_id AND r.plan_id=e.plan_id WHERE e.plan_id=? AND e.patient_id=? AND e.event_type IN ("+types(allowed)+")";
        if(!clinical)sql+=" AND r.status='PUBLISHED'";
        List<Object> args=new ArrayList<>(Arrays.asList(planId,patient));
        if(after!=null){sql+=" AND (e.recorded_at<? OR (e.recorded_at=? AND e.id<?))";Instant at=CarePlanContracts.parseOffsetInstant(after[0]);Collections.addAll(args,at,at,Long.parseLong(after[1]));}
        sql+=" ORDER BY e.recorded_at DESC,e.id DESC LIMIT ?";args.add(limit+1);final String statement=sql;
        List<Map<String,Object>> rows=jdbc.query(connection->{PreparedStatement ps=connection.prepareStatement(statement);bind(ps,args);return ps;},(rs,i)->map("id",rs.getLong("id"),"actorId",rs.getLong("actor_id"),"actorName",rs.getString("actor_name"),"actorRole",rs.getString("actor_role"),"entryMode",rs.getString("entry_mode"),"eventType",rs.getString("event_type"),"note",rs.getString("note"),"occurredAt",time(rs,"occurred_at"),"recordedAt",time(rs,"recorded_at")));
        String next=null;if(rows.size()>limit){rows.remove(rows.size()-1);Map<String,Object> last=rows.get(rows.size()-1);next=encode(actorId,planId,visibility,(String)last.get("recordedAt"),id(last.get("id")));}
        for(Map<String,Object> event:rows)event.put("evidence",evidence(actorId,patient,id(event.get("id"))));
        auth.requireRead(actorId,patient);if(clinical!=clinical(actorId,patient))throw CarePlanException.denied();
        return map("items",rows,"nextCursor",next);
    }
    private List<Map<String,Object>> evidence(long actor,long patient,long event){
        List<Map<String,Object>> rows=jdbc.query("SELECT source_type,source_id FROM care_plan_evidence WHERE event_id=? ORDER BY id",(rs,i)->map("sourceType",rs.getString("source_type"),"sourceId",rs.getLong("source_id")),event);
        for(Map<String,Object> ref:rows){String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));boolean readable=auth.canReadEvidence(actor,patient,type,source);ref.put("restricted",!readable);
            if(readable){String table="MEASUREMENT".equals(type)?"health_measurement":"medical_record",column="MEASUREMENT".equals(type)?"metric_type":"record_type";
                List<String> titles=jdbc.queryForList("SELECT "+column+" FROM "+table+" WHERE id=? AND patient_id=?",String.class,source,patient);if(titles.isEmpty())ref.put("restricted",true);else ref.put("title",titles.get(0));}
        }return rows;
    }
    private boolean clinical(long actor,long patient){try{auth.requireClinical(actor,patient);return true;}catch(CarePlanException denied){if(denied.getStatus()!=403)throw denied;return false;}}
    private static String types(List<String> types){return "'"+String.join("','",types)+"'";}
    private static void bind(PreparedStatement ps,List<Object> args)throws SQLException{for(int i=0;i<args.size();i++){Object value=args.get(i);if(value instanceof Instant)time(ps,i+1,(Instant)value);else ps.setObject(i+1,value);}}
    private static String encode(long actor,long plan,String visibility,String at,long id){return Base64.getUrlEncoder().withoutPadding().encodeToString(("EVENT|"+actor+"|"+plan+"|"+visibility+"|"+at+"|"+id).getBytes(StandardCharsets.UTF_8));}
    private static String[] cursor(String cursor,long actor,long plan,String visibility){
        if(cursor==null)return null;try{if(cursor.length()>512||!cursor.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();String[]parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=6||!"EVENT".equals(parts[0])||!Long.toString(actor).equals(parts[1])||!Long.toString(plan).equals(parts[2])||!visibility.equals(parts[3])||!parts[5].matches("[1-9][0-9]*")||Long.parseLong(parts[5])<=0)throw new IllegalArgumentException();CarePlanContracts.parseOffsetInstant(parts[4]);return new String[]{parts[4],parts[5]};
        }catch(IllegalArgumentException ex){throw CarePlanException.invalid("Invalid care-plan event cursor.");}
    }
    private static String title(String type){
        switch(type){case "PLAN_PUBLISHED":return "Care plan published";case "REVISION_PUBLISHED":return "Care plan revised";case "RECEIPT_SUBMITTED":return "Care action submitted";case "RECEIPT_CONFIRMED":return "Care action confirmed";case "RECEIPT_RETURNED":return "Care action returned";case "PLAN_CANCELLED":return "Care plan cancelled";case "PLAN_CLOSED":return "Care plan completed";default:throw new IllegalArgumentException("Unsupported care-plan timeline event.");}
    }
}
