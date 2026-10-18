package org.familyhealthcare.service.alertworkflow;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;

/** 测量事项与事件的有界摘要；不加载原详情、证据正文、通知或来源数值。 */
@Service
public class MeasurementItemQueryService {
    private static final String ALERT_COLUMNS="id,patient_id,workflow_version,source_type,source_id,metric_key,workflow_state,workflow_mode,cycle_no";
    private static final String EVENT_COLUMNS="id,alert_id,patient_id,event_type,cycle_no,from_status,to_status,occurred_at,recorded_at";
    private static final Set<String> EVENT_TYPES=new HashSet<>(AlertWorkflowService.OPERATIONS);
    static {EVENT_TYPES.addAll(Arrays.asList("CREATED","SOURCE_CHANGED","DUE"));}
    private final JdbcTemplate jdbc;
    private final AlertWorkflowAuthorizationService auth;
    private final AlertWorkflowProperties properties;
    private final MeasurementReadReadiness readiness;
    public MeasurementItemQueryService(JdbcTemplate jdbc,AlertWorkflowAuthorizationService auth,AlertWorkflowProperties properties,MeasurementReadReadiness readiness){this.jdbc=jdbc;this.auth=auth;this.properties=properties;this.readiness=readiness;}

    public Map<String,Object> page(long actor,long patient,String cursor,int limit){
        validate(actor,patient,limit);Cursor codec=new Cursor(actor,patient,null);Map<String,Object> position=codec.decode(cursor);
        try{
            auth.requireRead(actor,patient);readiness.requireItems();
            List<Map<String,Object>> candidates=alerts(patient,position,limit);
            // 包含预读行一起核对身份，但只消费前limit行；不能为补满页面循环查询或跳过预读行。
            Map<String,Set<Long>> sources=sources(patient,candidates);
            boolean more=candidates.size()>limit;int consumed=Math.min(limit,candidates.size());List<Map<String,Object>> items=new ArrayList<>();
            for(Map<String,Object> row:candidates.subList(0,consumed))if(legal(row)&&readable(row,sources))items.add(summary(row));
            String next=more?codec.encode(candidates.get(consumed-1)):null;
            Map<String,Object> result=map("patientId",patient,"generatedAt",properties.now().toString(),"items",items,"nextCursor",next,"summaryOnly",true,
                    "coverage",coverage(more,"STATE_DUE_ID_ORDER","事项按状态和截止时间分组，并非发生时间顺序。本页不是快照，修改可能使事项重复或移过当前翻页位置，请从第一页刷新。本页为空时仍可能有下一页。"));
            auth.requireRead(actor,patient);return result;
        }catch(AlertWorkflowException failure){throw failure;}
        catch(DataAccessException|IllegalStateException failure){throw MeasurementReadReadiness.unavailable();}
    }

    public Map<String,Object> events(long actor,long patient,long alertId,String cursor,int limit){
        validate(actor,patient,limit);if(alertId<=0)throw AlertWorkflowException.invalid("事项编号必须是正整数。");
        Cursor codec=new Cursor(actor,patient,alertId);Map<String,Object> position=codec.decode(cursor);
        try{
            auth.requireRead(actor,patient);readiness.requireSelectedItem();
            List<Map<String,Object>> alerts=jdbc.query("SELECT "+ALERT_COLUMNS+" FROM alert_record WHERE id=? AND patient_id=? AND workflow_version=1 LIMIT 1",(rs,index)->alertRow(rs,patient,false),alertId,patient);
            if(alerts.isEmpty()||!legal(alerts.get(0)))throw missing();Map<String,Object> alert=alerts.get(0);
            if(!readable(alert,sources(patient,alerts)))throw missing();
            readiness.requireItemEvents();
            StringBuilder sql=new StringBuilder("SELECT "+EVENT_COLUMNS+" FROM alert_event WHERE alert_id=?");List<Object> args=new ArrayList<>();args.add(alertId);
            if(position!=null){sql.append(" AND (cycle_no>? OR (cycle_no=? AND id>?))");args.add(position.get("cycle"));args.add(position.get("cycle"));args.add(position.get("id"));}
            sql.append(" ORDER BY cycle_no,id LIMIT ?");args.add(limit+1);
            // 患者列不能在WHERE中隐藏已损坏的事件归属；含预读行的每条事件都必须一致。
            List<Map<String,Object>> candidates=jdbc.query(sql.toString(),(rs,index)->eventRow(rs,patient,alertId),args.toArray());
            boolean more=candidates.size()>limit;List<Map<String,Object>> rows=new ArrayList<>(candidates.subList(0,Math.min(limit,candidates.size())));
            String next=more?codec.encode(rows.get(rows.size()-1)):null;
            Map<String,Object> result=map("patientId",patient,"alertId",alertId,"sourceType",alert.get("sourceType"),"sourceId",alert.get("sourceId"),"generatedAt",properties.now().toString(),"summaryOnly",true,"items",rows,"nextCursor",next,
                    "coverage",coverage(more,"CYCLE_REGISTRATION_ORDER","这里只展示周期内登记顺序的事件摘要，详细说明与证据请在获授权的原随访中查看。本页不是全部证据，也不是快照；资料变化后请从第一页刷新。"));
            auth.requireRead(actor,patient);return result;
        }catch(AlertWorkflowException failure){throw failure;}
        catch(DataAccessException|IllegalStateException failure){throw MeasurementReadReadiness.unavailable();}
    }
    private List<Map<String,Object>> alerts(long patient,Map<String,Object> cursor,int limit){
        StringBuilder sql=new StringBuilder("SELECT "+ALERT_COLUMNS+",due_at FROM alert_record WHERE patient_id=? AND workflow_version=1");List<Object> args=new ArrayList<>();args.add(patient);
        if(cursor!=null){
            // 按现有索引的原始升序：NULL在前，状态和截止时间都不得归一化或补值。
            if(cursor.get("state")==null)sql.append(" AND (workflow_state IS NOT NULL OR (workflow_state IS NULL AND ");
            else{sql.append(" AND (workflow_state>? OR (workflow_state=? AND ");args.add(cursor.get("state"));args.add(cursor.get("state"));}
            if(cursor.get("due")==null){sql.append("(due_at IS NOT NULL OR (due_at IS NULL AND id>?))");args.add(cursor.get("id"));}
            else{sql.append("(due_at>? OR (due_at=? AND id>?))");args.add(cursor.get("due"));args.add(cursor.get("due"));args.add(cursor.get("id"));}
            sql.append("))");
        }
        sql.append(" ORDER BY workflow_state,due_at,id LIMIT ?");args.add(limit+1);
        return jdbc.query(sql.toString(),(rs,index)->alertRow(rs,patient,true),args.toArray());
    }
    private Map<String,Set<Long>> sources(long patient,List<Map<String,Object>> alerts){
        Map<String,Set<Long>> requested=new LinkedHashMap<>();
        for(Map<String,Object> row:alerts){String type=(String)row.get("sourceType");Long id=(Long)row.get("sourceId");if(AlertWorkflowContracts.SOURCE_TYPES.contains(type)&&id!=null&&id>0)requested.computeIfAbsent(type,key->new LinkedHashSet<>()).add(id);}
        if(!requested.isEmpty())readiness.requireItemSources(requested.keySet());
        Map<String,Set<Long>> result=new HashMap<>();
        for(Map.Entry<String,Set<Long>> entry:requested.entrySet()){
            String type=entry.getKey();boolean self="BP_SELF_MONITOR".equals(type);Set<Long> owned=new HashSet<>();result.put(type,owned);
            String table=self?"bp_self_monitor_record":"health_measurement";
            String sql="SELECT n.id,n.patient_id,i.source_id AS identity_source_id,i.patient_id AS identity_patient_id,i.status AS identity_status,i.current_revision,i.lock_version FROM "+table+" n LEFT JOIN alert_source_identity i ON i.source_type=? AND i.source_id=n.id WHERE n.id IN ("+String.join(",",Collections.nCopies(entry.getValue().size(),"?"))+") LIMIT ?";
            List<Object> args=new ArrayList<>();args.add(type);args.addAll(entry.getValue());args.add(entry.getValue().size());
            jdbc.query(sql,rs->{
                long id=rs.getLong("id");Long owner=nullableId(rs,"patient_id"),identity=nullableId(rs,"identity_source_id"),identityPatient=nullableId(rs,"identity_patient_id"),revision=nullableId(rs,"current_revision"),version=nullableId(rs,"lock_version");
                if(id<=0||!entry.getValue().contains(id))throw MeasurementReadReadiness.unavailable();
                // JOIN只按来源身份，不能用患者条件把已知错配伪装成没有身份。
                if(identity!=null&&(identity!=id||!Long.valueOf(patient).equals(identityPatient)||!Objects.equals(owner,identityPatient)||revision==null||revision<1||version==null||version<0||!Arrays.asList("ACTIVE","INVALIDATED").contains(rs.getString("identity_status"))))throw MeasurementReadReadiness.unavailable();
                if(Long.valueOf(patient).equals(owner))owned.add(id);
            },args.toArray());
        }
        return result;
    }
    private Map<String,Object> alertRow(ResultSet rs,long patient,boolean picker)throws SQLException {
        long id=rs.getLong("id");if(id<=0||rs.getLong("patient_id")!=patient||rs.getLong("workflow_version")!=1)throw MeasurementReadReadiness.unavailable();
        Map<String,Object> row=map("alertId",id,"sourceType",rs.getString("source_type"),"sourceId",nullableId(rs,"source_id"),"metric",rs.getString("metric_key"),"state",rs.getString("workflow_state"),"mode",rs.getString("workflow_mode"),"cycleNo",rs.getLong("cycle_no"));
        if(picker)row.putAll(map("dueAt",time(rs,"due_at"),"dueKey",rs.getString("due_at")));return row;
    }
    private Map<String,Object> eventRow(ResultSet rs,long patient,long alert)throws SQLException {
        long id=rs.getLong("id"),cycle=rs.getLong("cycle_no");String event=rs.getString("event_type"),from=rs.getString("from_status"),to=rs.getString("to_status");
        if(id<=0||cycle<1||rs.getLong("alert_id")!=alert||rs.getLong("patient_id")!=patient||!EVENT_TYPES.contains(event)||from!=null&&!AlertWorkflowContracts.STATES.contains(from)||!AlertWorkflowContracts.STATES.contains(to))throw MeasurementReadReadiness.unavailable();
        String recorded=time(rs,"recorded_at");if(recorded==null)throw MeasurementReadReadiness.unavailable();
        return map("id",id,"eventType",event,"cycleNo",cycle,"fromStatus",from,"toStatus",to,"occurredAt",time(rs,"occurred_at"),"recordedAt",recorded);
    }
    /** 事项使用冻结指标；当前来源更正为其他指标或作废不抹掉获权历史。 */
    private static boolean legal(Map<String,Object> row){return AlertWorkflowContracts.SOURCE_TYPES.contains(row.get("sourceType"))&&row.get("sourceId")!=null&&(Long)row.get("sourceId")>0&&AlertWorkflowContracts.METRICS.contains(row.get("metric"))&&(!"BP_SELF_MONITOR".equals(row.get("sourceType"))||Arrays.asList("BP","GLUCOSE").contains(row.get("metric")))&&AlertWorkflowContracts.STATES.contains(row.get("state"))&&AlertWorkflowContracts.MODES.contains(row.get("mode"))&&(Long)row.get("cycleNo")>0;}
    private static boolean readable(Map<String,Object> row,Map<String,Set<Long>> sources){
        Set<Long> nativeRows=sources.get(row.get("sourceType"));return nativeRows!=null&&nativeRows.contains(row.get("sourceId"));
    }
    private static Map<String,Object> summary(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>(row);result.remove("dueKey");return result;}
    private static Map<String,Object> coverage(boolean more,String order,String notice){return map("scope","CURRENT_PAGE","order",order,"consistency","NON_SNAPSHOT","hasMore",more,"refreshFromFirstPage",true,"notice",notice);}
    private static void validate(long actor,long patient,int limit){if(actor<=0||patient<=0||limit<1||limit>100)throw AlertWorkflowException.invalid("患者编号或每页数量无效。");}
    private static AlertWorkflowException missing(){return new AlertWorkflowException(404,"ITEM_NOT_FOUND","该测量随访不存在，或无法在当前患者下查看。");}

    /** 两个固定端点专用的位置协议；游标不是授权，不保存快照或全量统计。 */
    private static final class Cursor {
        private static final int MAX_ENCODED_LENGTH=2048;
        private final Map<String,Object> context;private final boolean events;
        Cursor(long actor,long patient,Long alert){events=alert!=null;context=map("endpoint",events?"measurement-item-events":"measurement-items","protocol",2L,"actor",actor,"patient",patient,"order",events?"cycle_no,id":"workflow_state,due_at,id","filter","WORKFLOW_V1_MEASUREMENTS_V2");if(events)context.put("alert",alert);}
        Map<String,Object> decode(String encoded){
            if(encoded==null)return null;
            try{
                if(encoded.isEmpty()||encoded.length()>MAX_ENCODED_LENGTH||!encoded.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
                Map<String,Object> value=object(new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8));Set<String> keys=new HashSet<>(context.keySet());keys.add("id");keys.addAll(events?Collections.singletonList("cycle"):Arrays.asList("state","due"));
                if(!value.keySet().equals(keys))throw new IllegalArgumentException();for(Map.Entry<String,Object> entry:context.entrySet())if(!entry.getValue().equals(value.get(entry.getKey())))throw new IllegalArgumentException();
                positive(value.get("id"));if(events)positive(value.get("cycle"));else{
                    if(value.get("state")!=null&&!(value.get("state") instanceof String)||value.get("due")!=null&&!(value.get("due") instanceof String))throw new IllegalArgumentException();
                    if(value.get("due")!=null){String due=(String)value.get("due");LocalDateTime time=LocalDateTime.parse(due.replace(' ','T'));if(time.getYear()<1000||time.getYear()>9999)throw new IllegalArgumentException();}
                }
                return value;
            }catch(RuntimeException invalid){throw AlertWorkflowException.invalid("此测量查询的翻页位置无效。");}
        }
        String encode(Map<String,Object> row){Map<String,Object> value=new LinkedHashMap<>(context);value.put("id",row.get(events?"id":"alertId"));if(events)value.put("cycle",row.get("cycleNo"));else{value.put("state",row.get("state"));value.put("due",row.get("dueKey"));}
            String result=Base64.getUrlEncoder().withoutPadding().encodeToString(json(value).getBytes(StandardCharsets.UTF_8));
            // 输出和输入使用同一预算，保留原键；不能返回自己无法接受的下一页游标。
            if(result.length()>MAX_ENCODED_LENGTH)throw MeasurementReadReadiness.unavailable();
            try{decode(result);}catch(AlertWorkflowException invalidPosition){throw MeasurementReadReadiness.unavailable();}return result;
        }
        private static void positive(Object value){if(!(value instanceof Long)||(Long)value<=0)throw new IllegalArgumentException();}
    }
}
