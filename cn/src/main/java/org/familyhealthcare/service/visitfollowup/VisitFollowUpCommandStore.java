package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.workflow.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpRepository.*;

/** 同一原始信封只关闭一次回执；当前资格不进入缓存，每次披露重新核对。 */
public final class VisitFollowUpCommandStore {
    private VisitFollowUpCommandStore() {
    }
    public static Map<String,Object> executeAppointment(long actor,Command command,VisitFollowUpAdmission.Context admission,Supplier<Map<String,Object>> change) {
        if(admission==null||command==null||command.isVisitBinding())throw integrity();
        return execute(actor,command,admission.scope(),false,change);
    }
    public static Map<String,Object> executeVisitBinding(long actor,Command command,VisitFollowUpAdmission.VisitBindingContext admission,Supplier<Map<String,Object>> change) {
        if(admission==null||command==null||!command.isVisitBinding())throw integrity();
        return execute(actor,command,admission.scope(),true,change);
    }
    private static Map<String,Object> execute(long actor,Command command,VisitFollowUpAdmission.Scope scope,boolean binding,Supplier<Map<String,Object>> change) {
        Objects.requireNonNull(change);
        scope.start(actor,command,binding);
        VisitFollowUpRepository repo=scope.repository();
        String hash=WorkflowData.hash(envelope(actor,command));
        if(scope.admittedReceipt!=null)return replay(scope,hash);
        scope.requireEnabled();
        Long stateId=null;
        if(command.getSource()!=null) {
            Map<String,Object> state=repo.state(command.getSource());
            stateId=state==null?null:id(state.get("id"));
            long version=state==null?0:number(state.get("lock_version"));
            if(version!=command.getExpectedVersion()||!repo.source(command.getSource()).getFingerprint().equals(command.getExpectedSourceFingerprint()))throw new WorkflowException(409,"SOURCE_CHANGED","来源已变化，请刷新比较后再明确决定。");
        }
        try {
            if(repo.update("INSERT INTO visit_followup_command(actor_id,command_key,patient_id,state_id,expected_version,operation,payload_hash,created_at) VALUES(?,?,?,?,?,?,?,?)",actor,command.getCommandKey(),command.getPatientId(),stateId,command.getExpectedVersion(),command.getOperation().name(),hash,scope.now())!=1)throw integrity();
        }
        catch(DuplicateKeyException duplicate) {
            return replay(scope,hash);
        }
        AtomicReference<Map<String,Object>> completed=new AtomicReference<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                scope.requireCurrent();
                Map<String,Object> result=completed.get();
                repo.requireCompleted(scope,result);
                Map<String,Object> row=repo.command(actor,command.getCommandKey(),false);
                if(row==null||!hash.equals(row.get("payload_hash"))||row.get("result_json")==null||!canonical(result).equals(canonical(storedReceipt(String.valueOf(row.get("result_json"))))))throw integrity();
            }
        });
        // 保留命令且提交闭合门已注册后，才开放写能力；吞掉前置拒绝也不能继续写。
        scope.mutationActive=true;
        scope.requireCurrent();
        Map<String,Object> result=receipt(change.get());
        validateResult(command,result);
        repo.requireCompleted(scope,result);
        scope.requireCurrent();
        if(repo.update("UPDATE visit_followup_command SET state_id=?,result_json=? WHERE actor_id=? AND command_key=? AND patient_id=? AND payload_hash=? AND result_json IS NULL",result.get("workflowId"),WorkflowData.json(result),actor,command.getCommandKey(),command.getPatientId(),hash)!=1)throw integrity();
        completed.set(result);
        scope.sealed=true;
        scope.requireCurrent();
        return result;
    }
    private static Map<String,Object> replay(VisitFollowUpAdmission.Scope scope,String hash) {
        VisitFollowUpRepository repo=scope.repository();
        Command c=scope.command;
        scope.requireCurrent();
        Map<String,Object> row=repo.command(scope.actor,c.getCommandKey(),true);
        if(row==null)throw new WorkflowException(409,"COMMAND_RESULT_UNKNOWN","原请求结果尚未确认，请保留原请求重试。");
        if(number(row.get("patient_id"))!=c.getPatientId()||number(row.get("expected_version"))!=c.getExpectedVersion()||!c.getOperation().name().equals(row.get("operation"))||!hash.equals(row.get("payload_hash")))throw new WorkflowException(409,"COMMAND_CONFLICT","此请求标识已用于不同内容的操作。");
        if(row.get("result_json")==null||number(row.get("receipt_bytes"))>512)throw integrity();
        Map<String,Object> result=storedReceipt(String.valueOf(row.get("result_json")));
        validateResult(c,result);
        if(number(row.get("state_id"))!=number(result.get("workflowId")))throw integrity();
        Map<String,Object> state=repo.state(number(result.get("workflowId"))),event=repo.event(number(result.get("eventId")));
        if(state==null||event==null||number(state.get("patient_id"))!=c.getPatientId()||number(event.get("patient_id"))!=c.getPatientId()||number(event.get("state_id"))!=number(result.get("workflowId"))||number(event.get("state_version"))!=number(result.get("version"))||number(event.get("actor_id"))!=scope.actor||!c.getCommandKey().equals(event.get("command_key"))||!c.getOperation().name().equals(event.get("event_type"))||!reference(state).equals(new SourceRef(SourceKind.valueOf(String.valueOf(result.get("sourceKind"))),id(result.get("sourceId")))))throw integrity();
        repo.requireReceiptSuccessor(scope,result,false);
        scope.sealed=true;
        scope.requireCurrent();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                scope.requireCurrent();
            }
        });
        return result;
    }
    private static void validateResult(Command command,Map<String,Object> result) {
        if(!command.getOperation().name().equals(result.get("operation"))||!command.getSourceKind().name().equals(result.get("sourceKind"))||number(result.get("version"))!=nextVersion(command.getExpectedVersion())||(command.getSourceId()!=null&&!command.getSourceId().equals(result.get("sourceId"))))throw integrity();
    }
}
