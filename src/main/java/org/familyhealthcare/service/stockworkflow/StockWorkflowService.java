package org.familyhealthcare.service.stockworkflow;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.stockworkflow.StockWorkflowContracts.*;
import static org.familyhealthcare.service.stockworkflow.StockWorkflowRepository.conflict;
/** One native balance and append-only native movements. Explicit human evidence never implies treatment or safety. */
@Service public class StockWorkflowService  {
    private final JdbcTemplate jdbc;
    private final StockWorkflowProperties properties;
    private final StockWorkflowAuthorizationService auth;
    private final StockWorkflowRepository repo;
    private final StockWorkflowEventStore events;
    public StockWorkflowService(JdbcTemplate jdbc,StockWorkflowProperties properties,StockWorkflowAuthorizationService auth,StockWorkflowRepository repo,StockWorkflowEventStore events)  {
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.events=events;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> command(long actor,Long stockId,StockCommand c)  {
        mutationTransaction(jdbc);
        if(stockId==null)throw WorkflowException.invalid("Select an existing native stock.");
        auth.requireWrite(actor,c.patientId);
        repo.lockCommand(actor,c.patientId,stockId,c);
        current(actor,c.patientId,stockId);
        Map<String,Object> result=WorkflowCommandExecutor.execute(jdbc,WorkflowCommandExecutor.Domain.STOCK,actor,c.commandKey,c.payload(stockId),stockId,c.expectedVersion,properties.now(),()->  {
            if(!properties.isEnabled()&&!"CANCEL_MISTAKE".equals(c.operation))throw new WorkflowException(503,"FEATURE_DISABLED","Stock workflow is disabled.");
            Map<String,Object> s=current(actor,c.patientId,stockId);
            if(id(s.get("lock_version"))!=c.expectedVersion)throw conflict("VERSION_CONFLICT");
            long version=nextVersion(s);
            Map<String,Object> w=s.get("current_work_id")==null?null:repo.work(id(s.get("current_work_id")));
            Long count=null,resolution=null,movement=null;
            Map<String,Object> evidence=new LinkedHashMap<>();
            String close=null;
            if("ADOPT_STOCK".equals(c.operation))  {
                if(id(s.get("workflow_version"))!=0)throw conflict("STOCK_ADOPTED");
                requireUnit(s,c.unit);
                auth.requireWrite(c.assignedUserId,c.patientId);
                Map<String,Object> history=history(s);
                w=open(actor,s,null,c.assignedUserId,c.dailyUseQuantity,history);
                repo.update("UPDATE medication_stock SET workflow_version=1,current_work_id=?,adopted_at=? WHERE id=?",w.get("id"),properties.now(),stockId);
                s=repo.stock(stockId);
                evidence.putAll(history);
            }
            else  {
                if(w==null||id(s.get("workflow_version"))!=1)throw conflict("STOCK_NOT_ADOPTED");
                if(!Arrays.asList("CHANGE_RULE","CANCEL_MISTAKE","RESOLVE_INTAKE").contains(c.operation)&&(w.get("assigned_user_id")==null||!auth.canWrite(id(w.get("assigned_user_id")),c.patientId)))throw conflict("RESPONSIBLE_WRITER_REQUIRED");
                if("RESOLVE_INTAKE".equals(c.operation))  {
                    Map<String,Object> a=repo.one("SELECT * FROM care_intake_action WHERE id=?",c.actionId);
                    if(a==null||id(a.get("intake_id"))!=c.intakeId||id(a.get("patient_id"))!=c.patientId)throw StockWorkflowAuthorizationService.denied();
                    RecordedIntakeAction action=from(a,s);
                    IntakeCorrection correction="CORRECTION".equals(a.get("action_kind"))?new IntakeCorrection(action,id(a.get("original_action_id")),action.actionId,c.commandKey,(String)a.get("correction_reason"),c.countChoice,c.countId,c.countVersion,(String)object((String)a.get("provenance_json")).get("historicalUnit")):null;
                    ConsumptionResult r=settle(actor,s,w,action,c.countChoice,c.countId,c.countVersion,c.commandKey,correction,c.reason,true);
                    count=c.countId;
                    resolution=r.resolutionId;
                    evidence.put("stockEventId",r.stockEventId);
                    evidence.put("outcome",r.outcome);
                    s=repo.stock(stockId);
                    w=repo.work(id(s.get("current_work_id")));
                    version=nextVersion(s);
                }
                else if("RECORD_REPLENISHMENT".equals(c.operation))  {
                    requireOpen(w);
                    requireUnit(s,c.unit);
                    past(c.actualAt);
                    Map<String,Object> last=latestCount(s,w);
                    if(last!=null&&!c.actualAt.isAfter(at(last.get("counted_at"))))throw conflict("REPLENISHMENT_ALREADY_COUNTED");
                    movement=move(actor,s,c.quantity,"REPLENISHMENT:"+c.commandKey,"REPLENISHMENT",c.actualAt,c.reason,null,null,null);
                    evidence.put("actualAt",c.actualAt.toString());
                    evidence.put("quantity",c.quantity.toPlainString());
                }
                else if("RECORD_COUNT".equals(c.operation))  {
                    requireOpen(w);
                    requireUnit(s,c.unit);
                    past(c.countedAt);
                    Map<String,Object> h=history(s);
                    h.put("currentRule",ruleEvidence(s,w));
                    requireCountTime(h,c.countedAt);
                    String movements=json(h.get("movements")),actions=json(h.get("actions"));
                    Map<String,Object> resolutions=resolutionWatermark(s,actor);
                    resolutions.put("currentRule",ruleEvidence(s,w));
                    String reviewed=json(resolutions);
                    String digest=hash(json(map("movements",h.get("movements"),"actions",h.get("actions"),"resolutions",resolutions)));
                    count=repo.insert("INSERT INTO stock_count(stock_id,work_id,patient_id,medication_id,cycle,stock_version,quantity,unit,counted_at,actor_id,reason,prior_movements_json,prior_actions_json,reviewed_resolutions_json,evidence_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",stockId,w.get("id"),c.patientId,s.get("medication_id"),w.get("cycle"),version,c.quantity,s.get("unit"),c.countedAt,actor,c.reason,movements,actions,reviewed,digest,properties.now());
                    movement=move(actor,s,c.quantity.subtract(q(s)),"COUNT:"+count,"COUNT",c.countedAt,c.reason,null,null,count);
                    evidence.putAll(h);
                    evidence.put("countHash",digest);
                }
                else if("RECONCILE".equals(c.operation))  {
                    requireOpen(w);
                    Map<String,Object> h=history(s);
                    h.put("currentRule",ruleEvidence(s,w));
                    if(w.get("daily_use_quantity")==null)throw conflict("USAGE_UNKNOWN");
                    if(low(s,w))throw conflict("STOCK_STILL_LOW");
                    if(repo.unresolved(c.patientId,id(s.get("medication_id")))!=0)throw conflict("INTAKES_UNRESOLVED");
                    Map<String,Object> currentCount=latestCount(s,w);
                    if(currentCount==null)throw conflict("CURRENT_CYCLE_COUNT_REQUIRED");
                    evidence.putAll(h);
                    evidence.put("countId",currentCount.get("id"));
                    evidence.put("resolutions",resolutionWatermark(s,actor));
                    close="RESOLVED";
                }
                else if("CHANGE_RULE".equals(c.operation))  {
                    requireUnit(s,c.unit);
                    Long responsible=c.assignedUserId==null?number(w.get("assigned_user_id")):c.assignedUserId;
                    if(responsible==null||!auth.canWrite(responsible,c.patientId))throw conflict("RESPONSIBLE_WRITER_REQUIRED");
                    boolean before=low(s,w),wasOpen="OPEN".equals(w.get("state"));
                    evidence.put("beforeRule",rule(s,w));
                    evidence.put("beforeResponsibleUserId",w.get("assigned_user_id"));
                    evidence.put("priorRuleEvidence",ruleEvidence(s,w));
                    evidence.put("reasonCode","RULE_CHANGED");
                    repo.update("UPDATE medication_stock SET warning_quantity=?,warning_days=? WHERE id=?",c.warningQuantity,c.warningDays,stockId);
                    s=repo.stock(stockId);
                    if(wasOpen) {
                        repo.update("UPDATE stock_work SET assigned_user_id=?,updated_at=? WHERE id=?",responsible,properties.now(),w.get("id"));
                        w=new LinkedHashMap<>(w);w.put("assigned_user_id",responsible);w.put("daily_use_quantity",c.dailyUseQuantity);
                    } else {
                        w=open(actor,s,w,responsible,c.dailyUseQuantity,map("reasonCode","RULE_CHANGED","beforeRule",evidence.get("beforeRule")));
                    }
                    evidence.put("afterRule",rule(s,w));
                    evidence.put("afterResponsibleUserId",w.get("assigned_user_id"));
                    if((wasOpen&&before||!wasOpen)&&!low(s,w))close="NOT_APPLICABLE";
                }
                else if("CANCEL_MISTAKE".equals(c.operation))  {
                    requireOpen(w);
                    close="CANCELLED_MISTAKE";
                    evidence.put("reasonCode","RECORDED_IN_ERROR");
                }
            }
            repo.update("UPDATE medication_stock SET lock_version=? WHERE id=?",version,stockId);
            long event=events.append(actor,s,w,version,c.operation,c.reason,evidence,c.intakeId,c.actionId,null,null,null,count,null,movement,null);
            if(close!=null)  {
                repo.update("UPDATE stock_work SET state=?,closed_at=?,closing_event_id=?,updated_at=? WHERE id=?",close,properties.now(),event,properties.now(),w.get("id"));
                w=repo.work(id(w.get("id")));
            }
            return map("stockId",stockId,"version",version,"eventId",event,"state",w.get("state"),"cycle",w.get("cycle"),"workId",w.get("id"),"countId",count,"resolutionId",resolution);
        }
        );
        requireMinimalReceipt(result);
        current(actor,c.patientId,stockId);
        return result;
    }
    public StockWorkflowRepository.PreparedConsumption prepareIntakeConsumption(long actor,long patient,long stock,long intake,Long original,Long count){return repo.prepareConsumption(actor,patient,stock,intake,original,count);}
    public void registerPreparedAction(StockWorkflowRepository.PreparedConsumption prepared,org.familyhealthcare.service.medicationexecution.MedicationExecutionRepository.InsertedAction action){repo.registerPreparedAction(prepared,action);}
    public void registerPreparedEvent(StockWorkflowRepository.PreparedConsumption prepared,long action,long event){repo.registerPreparedEvent(prepared,action,event);}
    /** Joins the caller's ordered READ_COMMITTED transaction. Never inserts or edits an intake/action. */
    public ConsumptionResult recordConsumptionLocked(long actor,long stockId,RecordedIntakeAction a)  {
        mutationTransaction(jdbc);
        auth.requireWrite(actor,a.patientId);
        repo.lockConsumption(actor,a.patientId,stockId,a,null,null);
        Map<String,Object> s=current(actor,a.patientId,stockId);
        validate(actor,s,a);
        if("CORRECTION".equals(repo.one("SELECT action_kind FROM care_intake_action WHERE id=?",a.actionId).get("action_kind")))throw conflict("CORRECTION_INTERFACE_REQUIRED");
        if(!properties.isEnabled())return new ConsumptionResult("UNRECONCILED",null,null,null,null,"STOCK_WORKFLOW_DISABLED");
        if(id(s.get("workflow_version"))!=1)return new ConsumptionResult("UNRECONCILED",null,null,null,null,"STOCK_NOT_ADOPTED");
        Map<String,Object> w=repo.work(id(s.get("current_work_id")));
        return settle(actor,s,w,a,null,null,null,null,null,"Persisted actual fact",false);
    }
    public ConsumptionResult correctConsumptionLocked(long actor,long stockId,IntakeCorrection c)  {
        mutationTransaction(jdbc);
        RecordedIntakeAction a=c.action;
        auth.requireWrite(actor,a.patientId);
        repo.lockConsumption(actor,a.patientId,stockId,a,c.originalActionId,c.countId);
        Map<String,Object> s=current(actor,a.patientId,stockId);
        validate(actor,s,a);
        Map<String,Object> correction=repo.one("SELECT * FROM care_intake_action WHERE id=?",c.correctionActionId),original=repo.one("SELECT * FROM care_intake_action WHERE id=?",c.originalActionId);
        if(original==null||id(original.get("patient_id"))!=a.patientId||id(original.get("intake_id"))!=a.intakeId||!Objects.equals(number(correction.get("original_action_id")),c.originalActionId)||!"CORRECTION".equals(correction.get("action_kind"))||!Objects.equals(correction.get("correction_reason"),c.reason))throw conflict("CORRECTION_EVIDENCE_MISMATCH");
        if(c.historicalUnit!=null&&!Objects.equals(object((String)correction.get("provenance_json")).get("historicalUnit"),c.historicalUnit))throw conflict("HISTORICAL_UNIT_ATTESTATION_REQUIRED");
        if(!properties.isEnabled())return new ConsumptionResult("UNRECONCILED",null,null,null,null,"STOCK_WORKFLOW_DISABLED");
        if(id(s.get("workflow_version"))!=1)return new ConsumptionResult("UNRECONCILED",null,null,null,null,"STOCK_NOT_ADOPTED");
        return settle(actor,s,repo.work(id(s.get("current_work_id"))),a,c.countChoice,c.countId,c.countVersion,c.commandKey,c,c.reason,false);
    }
    private ConsumptionResult settle(long actor,Map<String,Object> s,Map<String,Object> w,RecordedIntakeAction a,String choice,Long countId,Long countVersion,String commandKey,IntakeCorrection correction,String reason,boolean explicit)  {
        validate(actor,s,a);
        Map<String,Object> prior=repo.one("SELECT * FROM stock_event WHERE stock_id=? AND intake_id=? AND action_id=? AND settlement_status IS NOT NULL ORDER BY id DESC LIMIT 1",s.get("id"),a.intakeId,a.actionId);
        String actionHash=hash(json(a.values()));
        String requestHash=hash(json(map("actionHash",actionHash,"correctionKey",correction==null?null:correction.commandKey,"originalActionId",correction==null?null:correction.originalActionId,"historicalUnit",correction==null?null:correction.historicalUnit,"countChoice",choice,"countId",countId,"countVersion",countVersion)));
        if(prior!=null)  {
            if(!hash((String)prior.get("evidence_json")).equals(prior.get("evidence_hash")))throw conflict("SETTLEMENT_EVIDENCE_INVALID");
            Map<String,Object> oldEvidence=object((String)prior.get("evidence_json"));
            if(!Objects.equals(actionHash,oldEvidence.get("actionHash"))||correction!=null&&!Objects.equals(requestHash,oldEvidence.get("requestHash"))&&!(explicit&&"UNRECONCILED".equals(prior.get("settlement_status"))))throw conflict("ACTION_PAYLOAD_CONFLICT");
            if(!"UNRECONCILED".equals(prior.get("settlement_status"))||!explicit)return result(prior);
        }
        requireCurrentFact(a);
        Map<String,Object> original=repo.one("SELECT * FROM medication_stock_movement WHERE source_key=?", "INTAKE:"+a.intakeId);
        if(original!=null&&(id(original.get("patient_id"))!=a.patientId||id(original.get("medication_id"))!=a.medicationId))throw conflict("MOVEMENT_IDENTITY_MISMATCH");
        Long originalId=original==null?null:id(original.get("id"));
        Map<String,Object> currentCount=latestCount(s,w);
        boolean late=currentCount!=null&&(a.actualAt==null||!a.actualAt.isAfter(at(currentCount.get("counted_at"))));
        String blocked=null;
        boolean skipped="SKIPPED".equals(repo.one("SELECT status FROM care_intake_action WHERE id=?",a.actionId).get("status"));
        if(a.actualAt==null||(!skipped&&(a.quantity==null||a.unit==null)))blocked="ACTUAL_AMOUNT_UNKNOWN";
        else if(!skipped&&!Objects.equals(a.unit,s.get("unit")))blocked="UNIT_MISMATCH";
        else if(late&&choice==null)blocked="COUNT_CHOICE_REQUIRED";
        if(choice!=null&&!("ACTUAL_AMOUNT".equals(choice)&&currentCount==null&&countId==null&&countVersion==null))  {
            if(currentCount==null||countId==null||countVersion==null||id(currentCount.get("id"))!=countId||id(currentCount.get("stock_version"))!=countVersion||id(currentCount.get("work_id"))!=id(w.get("id")))throw conflict("COUNT_CHANGED");
            if(a.actualAt!=null&&a.actualAt.isAfter(at(currentCount.get("counted_at")))&&"INCLUDED_IN_COUNT".equals(choice))throw conflict("COUNT_BEFORE_FACT");
        }
        String historical=original==null?null:(String)original.get("unit");
        if(original!=null&&historical==null&&correction!=null)historical=correction.historicalUnit;
        if(original!=null&&(historical==null||!historical.equals(s.get("unit"))))blocked="HISTORICAL_UNIT_UNKNOWN";
        Map<String,Object> previousSettled=repo.lastSettled(id(s.get("id")),a.intakeId,a.patientId);
        BigDecimal delta=BigDecimal.ZERO;
        String outcome=blocked==null?"APPLIED":"UNRECONCILED";
        Long compensation=null,movement=null;
        boolean firstOriginal=false;
        if(blocked==null&&"INCLUDED_IN_COUNT".equals(choice))outcome="COUNT_INCLUDED";
        else if(blocked==null&&correction==null)  {
            if(original==null&&!skipped)delta=a.quantity.negate();
            else if(original!=null)  {
                Map<String,Object> owner=repo.one("SELECT * FROM stock_event WHERE original_movement_id=? AND settled_action_id=?",originalId,a.actionId);
                if(owner==null&&id(original.get("workflow_version"))!=0)throw conflict("ORIGINAL_MOVEMENT_CONFLICT");
            }
        }
        else if(blocked==null)  {
            // An explicit prior action's settled receipt is the baseline, including a count-included fact.
            // If the real historical original movement had no typed receipt, its signed native effect remains evidence.
            Map<String,Object> settled=previousSettled==null?null:repo.one("SELECT status,quantity,unit FROM care_intake_action WHERE id=? AND patient_id=? AND intake_id=?",previousSettled.get("settled_action_id"),a.patientId,a.intakeId);
            if(previousSettled!=null&&settled==null)throw conflict("SETTLEMENT_EVIDENCE_INVALID");
            firstOriginal=original==null&&!skipped&&(settled==null||"SKIPPED".equals(settled.get("status")));
            BigDecimal priorEffect;
            if(settled!=null&&"SKIPPED".equals(settled.get("status")))priorEffect=BigDecimal.ZERO;
            else if(settled!=null&&Objects.equals(settled.get("unit"),s.get("unit"))&&settled.get("quantity")!=null)priorEffect="TAKEN".equals(settled.get("status"))?new BigDecimal(settled.get("quantity").toString()).negate():BigDecimal.ZERO;
            else  {
                priorEffect=original==null?BigDecimal.ZERO:new BigDecimal(original.get("quantity").toString());
                Map<String,Object> sum=repo.one("SELECT COALESCE(SUM(quantity),0) AS total FROM medication_stock_movement WHERE patient_id=? AND medication_id=? AND intake_id=? AND movement_kind='CORRECTION'",a.patientId,a.medicationId,a.intakeId);
                priorEffect=priorEffect.add(new BigDecimal(sum.get("total").toString()));
            }
            delta=priorEffect.negate().subtract(skipped?BigDecimal.ZERO:a.quantity);
        }
        if(!"OPEN".equals(w.get("state"))&&(blocked!=null||delta.signum()!=0||correction!=null))w=open(actor,s,w,number(w.get("assigned_user_id")),w.get("daily_use_quantity")==null?null:new BigDecimal(w.get("daily_use_quantity").toString()),map("reasonCode","CLOSURE_EVIDENCE_CHANGED","actionId",a.actionId));
        if(blocked==null&&delta.signum()!=0)  {
            if(correction!=null&&!firstOriginal)  {
                compensation=move(actor,s,delta,"CORRECTION:"+commandKey,"CORRECTION",a.actualAt,reason,a.intakeId,a.actionId,null);
            }
            else  {
                movement=move(actor,s,delta,"INTAKE:"+a.intakeId,"INTAKE",a.actualAt,reason,a.intakeId,a.actionId,null);
                originalId=movement;
            }
        }
        long version=nextVersion(s);
        repo.update("UPDATE medication_stock SET lock_version=? WHERE id=?",version,s.get("id"));
        Map<String,Object> evidence=map("actionHash",actionHash,"requestHash",requestHash,"action",a.values(),"reasonCode",blocked,"countChoice",choice,"countVersion",countVersion,"historicalOriginalMovement",original==null?null:original.get("id"));
        evidence.put("previousSettledEventId",previousSettled==null?null:previousSettled.get("id"));
        if(correction!=null)evidence.put("correction",map("originalActionId",correction.originalActionId,"commandKey",correction.commandKey,"historicalUnit",correction.historicalUnit));
        long event=events.append(actor,s,w,version,correction==null?"CONSUMPTION":"CORRECTION",reason,evidence,a.intakeId,a.actionId,a.executionEventId,originalId,compensation,"COUNT_INCLUDED".equals(outcome)?countId:null,prior==null?null:id(prior.get("id")),movement,outcome);
        Map<String,Object> resolution=repo.one("SELECT * FROM stock_intake_resolution WHERE intake_id=?",a.intakeId);
        long resolutionId;
        if(resolution==null)resolutionId=repo.insert("INSERT INTO stock_intake_resolution(stock_id,patient_id,medication_id,intake_id,current_action_id,execution_event_id,state,stock_event_id,count_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",s.get("id"),a.patientId,a.medicationId,a.intakeId,a.actionId,a.executionEventId,outcome,event,"COUNT_INCLUDED".equals(outcome)?countId:null,properties.now(),properties.now());
        else  {
            if(id(resolution.get("stock_id"))!=id(s.get("id")))throw conflict("RESOLUTION_IDENTITY_MISMATCH");
            resolutionId=id(resolution.get("id"));
            repo.update("UPDATE stock_intake_resolution SET current_action_id=?,execution_event_id=?,state=?,stock_event_id=?,count_id=?,lock_version=lock_version+1,updated_at=? WHERE id=?",a.actionId,a.executionEventId,outcome,event,"COUNT_INCLUDED".equals(outcome)?countId:null,properties.now(),resolutionId);
        }
        return new ConsumptionResult(outcome,resolutionId,event,originalId,compensation,blocked);
    }
    private ConsumptionResult result(Map<String,Object> event)  {
        Map<String,Object> r=repo.one("SELECT id FROM stock_intake_resolution WHERE intake_id=? AND stock_id=? AND patient_id=?",event.get("intake_id"),event.get("stock_id"),event.get("patient_id"));
        return new ConsumptionResult((String)event.get("settlement_status"),r==null?null:id(r.get("id")),id(event.get("id")),number(event.get("original_movement_id")),number(event.get("compensation_movement_id")),(String)object((String)event.get("evidence_json")).get("reasonCode"));
    }
    /** First settlement must not supersede a persisted successor or move either native/current settlement backward.
     * The exact ancestry/anchor rows were already included in the single ordinary admission set. */
    private void requireCurrentFact(RecordedIntakeAction a) {
        if(repo.one("SELECT id FROM care_intake_action WHERE original_action_id=? AND intake_id=? AND patient_id=? LIMIT 1",a.actionId,a.intakeId,a.patientId)!=null)throw conflict("FACT_SUPERSEDED");
        Set<Long> anchors=new HashSet<>();
        Map<String,Object> intake=repo.one("SELECT latest_action_id FROM medication_intake WHERE id=? AND patient_id=?",a.intakeId,a.patientId);
        if(intake.get("latest_action_id")!=null)anchors.add(id(intake.get("latest_action_id")));
        Map<String,Object> resolution=repo.one("SELECT current_action_id FROM stock_intake_resolution WHERE intake_id=?",a.intakeId);
        if(resolution!=null)anchors.add(id(resolution.get("current_action_id")));
        Long cursor=a.actionId;Set<Long> seen=new HashSet<>();
        while(cursor!=null&&!anchors.isEmpty()) {
            if(!seen.add(cursor)||seen.size()>50)throw conflict("ACTION_CHAIN_INVALID");
            anchors.remove(cursor);
            Map<String,Object> row=repo.one("SELECT original_action_id FROM care_intake_action WHERE id=? AND intake_id=? AND patient_id=?",cursor,a.intakeId,a.patientId);
            if(row==null)throw conflict("ACTION_EVIDENCE_MISMATCH");
            cursor=number(row.get("original_action_id"));
        }
        if(!anchors.isEmpty())throw conflict("FACT_SUPERSEDED");
    }
    private void validate(long actor,Map<String,Object> s,RecordedIntakeAction a)  {
        if(a.patientId!=id(s.get("patient_id"))||a.medicationId!=id(s.get("medication_id")))throw StockWorkflowAuthorizationService.denied();
        auth.requireWrite(actor,a.patientId);
        Map<String,Object> row=repo.one("SELECT a.*,i.medication_id,i.execution_id AS intake_execution,i.latest_action_id FROM care_intake_action a JOIN medication_intake i ON i.id=a.intake_id AND i.patient_id=a.patient_id WHERE a.id=?",a.actionId),event=repo.one("SELECT * FROM medication_execution_event WHERE id=?",a.executionEventId);
        if(row==null||event==null||a.actorId!=id(row.get("actor_id"))||a.intakeId!=id(row.get("intake_id"))||a.patientId!=id(row.get("patient_id"))||a.medicationId!=id(row.get("medication_id"))||a.executionEventId!=id(row.get("execution_event_id"))||!Objects.equals(a.actualAt,at(row.get("actual_at_utc")))||!same(a.quantity,row.get("quantity"))||!Objects.equals(a.unit,row.get("unit"))||!Objects.equals(a.attribution,row.get("recorded_for"))||id(event.get("patient_id"))!=a.patientId||id(event.get("intake_id"))!=a.intakeId||id(event.get("action_id"))!=a.actionId||id(event.get("actor_id"))!=a.actorId||!Objects.equals(row.get("execution_id"),event.get("execution_id"))||!Objects.equals(row.get("intake_execution"),event.get("execution_id"))||!hash((String)event.get("evidence_json")).equals(event.get("evidence_hash")))throw conflict("ACTION_EVIDENCE_MISMATCH");
        if(a.actualAt!=null)past(a.actualAt);
        if(!Arrays.asList("TAKEN","SKIPPED").contains(row.get("status")))throw conflict("NOT_ACTUAL_FACT");
        if("SELF".equals(a.attribution)&&a.actorId!=auth.owner(a.patientId))throw StockWorkflowAuthorizationService.denied();
    }
    private RecordedIntakeAction from(Map<String,Object> a,Map<String,Object> s)  {
        if(a.get("execution_event_id")==null)throw conflict("GENUINE_FACT_CORRECTION_REQUIRED");
        return new RecordedIntakeAction(id(a.get("id")),id(a.get("intake_id")),id(a.get("execution_event_id")),id(a.get("patient_id")),id(s.get("medication_id")),id(a.get("actor_id")),at(a.get("actual_at_utc")),a.get("quantity")==null?null:new BigDecimal(a.get("quantity").toString()),(String)a.get("unit"),(String)a.get("recorded_for"));
    }
    private Map<String,Object> current(long actor,long patient,long stock)  {
        auth.requireWrite(actor,patient);
        Map<String,Object> s=repo.stock(stock);
        if(s==null||id(s.get("patient_id"))!=patient)throw StockWorkflowAuthorizationService.denied();
        return s;
    }
    private Map<String,Object> open(long actor,Map<String,Object> s,Map<String,Object> previous,Long assigned,BigDecimal daily,Map<String,Object> evidence)  {
        if(assigned!=null&&!auth.canWrite(assigned,id(s.get("patient_id")))) {
            if(previous==null)auth.requireWrite(assigned,id(s.get("patient_id")));
            // A historical responsible user cannot block a current writer's truthful fact.
            // Reopening keeps the lost-access evidence and requires explicit reassignment; no permission is granted.
            evidence=new LinkedHashMap<>(evidence);
            evidence.put("previousResponsibleAccessLost",assigned);
            assigned=null;
        }
        if(previous!=null&&id(previous.get("cycle"))>=Integer.MAX_VALUE)throw conflict("CYCLE_OVERFLOW");
        int cycle=previous==null?1:((Number)previous.get("cycle")).intValue()+1;
        String data=json(evidence);
        long work=repo.insert("INSERT INTO stock_work(stock_id,patient_id,medication_id,cycle,previous_work_id,state,assigned_user_id,opening_quantity,opening_unit,warning_quantity,warning_days,daily_use_quantity,opening_evidence_json,opening_evidence_hash,opened_at,created_by,updated_at) VALUES(?,?,?,?,?,'OPEN',?,?,?,?,?,?,?,?,?,?,?)",s.get("id"),s.get("patient_id"),s.get("medication_id"),cycle,previous==null?null:previous.get("id"),assigned,s.get("quantity"),s.get("unit"),s.get("warning_quantity"),s.get("warning_days"),daily,data,hash(data),properties.now(),actor,properties.now());
        repo.update("UPDATE medication_stock SET current_work_id=? WHERE id=?",work,s.get("id"));
        return repo.one("SELECT * FROM stock_work WHERE id=?",work);
    }
    private Map<String,Object> history(Map<String,Object> s)  {
        return map("movements",repo.watermark(id(s.get("patient_id")),id(s.get("medication_id")),false),"actions",repo.watermark(id(s.get("patient_id")),id(s.get("medication_id")),true));
    }
    private Map<String,Object> resolutionWatermark(Map<String,Object> s,long actor)  {
        Map<String,Object> value=repo.resolutionWatermark(id(s.get("id")),actor,properties.now());
        value.put("unresolved",repo.unresolved(id(s.get("patient_id")),id(s.get("medication_id"))));
        return value;
    }
    private void requireCountTime(Map<String,Object> h,Instant counted)  {
        for(String lane:Arrays.asList("movements","actions"))  {
            Object latest=((Map<?,?>)h.get(lane)).get("latestActualAt");
            if(latest!=null&&counted.isBefore(Instant.parse(latest.toString())))throw conflict("COUNT_BEFORE_KNOWN_CHANGE");
        }
    }
    private Map<String,Object> latestCount(Map<String,Object> s,Map<String,Object> w)  {
        return repo.one("SELECT * FROM stock_count WHERE stock_id=? AND work_id=? AND cycle=? ORDER BY id DESC LIMIT 1",s.get("id"),w.get("id"),w.get("cycle"));
    }
    private long move(long actor,Map<String,Object> s,BigDecimal delta,String key,String kind,Instant actual,String reason,Long intake,Long action,Long count)  {
        delta=exact(delta);
        BigDecimal balance=exact(q(repo.stock(id(s.get("id")))).add(delta));
        long movement=repo.insert("INSERT INTO medication_stock_movement(patient_id,medication_id,quantity,reason,source_key,actor_id,workflow_version,stock_id,intake_id,action_id,count_id,movement_kind,unit,actual_at_utc,created_at) VALUES(?,?,?,?,?,?,1,?,?,?,?,?,?,?,?)",s.get("patient_id"),s.get("medication_id"),delta,reason,key,actor,s.get("id"),intake,action,count,kind,s.get("unit"),actual,properties.now());
        repo.update("UPDATE medication_stock SET quantity=?,notified_at=NULL WHERE id=?",balance,s.get("id"));
        return movement;
    }
    private static Map<String,Object> rule(Map<String,Object> s,Map<String,Object> w) {
        return map("warningQuantity",new BigDecimal(s.get("warning_quantity").toString()).setScale(3).toPlainString(),"warningDays",s.get("warning_days"),"dailyUseQuantity",w.get("daily_use_quantity")==null?null:new BigDecimal(w.get("daily_use_quantity").toString()).setScale(3).toPlainString(),"unit",s.get("unit"));
    }
    private static Map<String,Object> ruleEvidence(Map<String,Object> s,Map<String,Object> w) {
        return map("rule",rule(s,w),"workId",w.get("id"),"cycle",w.get("cycle"),"kind",w.get("current_rule_event_id")==null?"OPENING":"CHANGE_RULE","eventId",w.get("current_rule_event_id"),"version",w.get("current_rule_event_id")==null?0:w.get("current_rule_version"),"hash",w.get("current_rule_event_id")==null?w.get("opening_evidence_hash"):w.get("current_rule_hash"));
    }
    public static boolean low(Map<String,Object> s,Map<String,Object> w)  {
        BigDecimal q=q(s);
        if(q.compareTo(new BigDecimal(s.get("warning_quantity").toString()))<=0)return true;
        return w.get("daily_use_quantity")!=null&&q.compareTo(new BigDecimal(w.get("daily_use_quantity").toString()).multiply(BigDecimal.valueOf(((Number)s.get("warning_days")).longValue())))<=0;
    }
    private void past(Instant at)  {
        if(at==null||at.isAfter(properties.now()))throw WorkflowException.invalid("Enter an actual non-future UTC time.");
    }
    private static void requireOpen(Map<String,Object> w)  {
        if(!"OPEN".equals(w.get("state")))throw conflict("WORK_CLOSED");
    }
    private static void requireUnit(Map<String,Object> s,String unit)  {
        if(!Objects.equals(s.get("unit"),unit))throw conflict("UNIT_MISMATCH");
    }
    private static BigDecimal q(Map<String,Object> s)  {
        return new BigDecimal(s.get("quantity").toString());
    }
    private static long nextVersion(Map<String,Object> s)  {
        long v=id(s.get("lock_version"));
        if(v>=9007199254740991L)throw conflict("VERSION_OVERFLOW");
        return v+1;
    }
    private static boolean same(BigDecimal a,Object b)  {
        return a==null?b==null:b!=null&&a.compareTo(new BigDecimal(b.toString()))==0;
    }
    private static Long number(Object o)  {
        return o==null?null:id(o);
    }
    private static Instant at(Object value)  {
        return value==null?null:value instanceof Instant?(Instant)value:((Timestamp)value).toInstant();
    }
}
