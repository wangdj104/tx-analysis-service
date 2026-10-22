package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 一次入口最多执行一个新命令；当前权限、原生内容和不可变事件在同一事务闭合。 */
@Service
public class MedicalRecordCorrectionService {
    private final MedicalRecordCorrectionRepository repository;
    private final MedicalRecordCorrectionAuthorization authorization;
    private final boolean enabled;
    @org.springframework.beans.factory.annotation.Autowired
    private org.familyhealthcare.service.medicalreview.MedicalReviewNativeLifecycle nativeLifecycle;
    public MedicalRecordCorrectionService(MedicalRecordCorrectionRepository repository, MedicalRecordCorrectionAuthorization authorization,
                                           @Value("${medical-record-correction.enabled:false}") boolean enabled) {
        this.repository=repository;this.authorization=authorization;this.enabled=enabled;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> execute(String body) {
        MedicalRecordCorrectionContracts.Command command=MedicalRecordCorrectionContracts.command(body);
        try (org.familyhealthcare.service.medicalreview.MedicalReviewNativeLifecycle.Scope scope=nativeLifecycle.openCorrection()) {
            try {
        repository.requireMutation();
        repository.requireNewCommands(repository.status(),enabled);
        long actor=authorization.actor();
        MedicalRecord source=authorization.lock(command.getRecordId(),actor,false);
        if(!Objects.equals(source.getPatientId(),command.getPatientId()))throw MedicalRecordCorrectionAuthorization.denied();
        MedicalRecordCorrectionRepository.State state=repository.state(source.getId(),true);
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        // 必须先交给 executor 处理旧 key；最新版本和摘要仅在 NEW 回调里比较。
        scope.admitCorrection(source);scope.arm();
        Map<String,Object> receipt=repository.execute(actor,command,now,()->change(command,source,state,actor,now,scope));
        scope.checkpoint();
        authorization.requireLocked(source,actor,false);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){authorization.requireLocked(source,actor,false);}
        });
        scope.completeCorrection(command,receipt);return receipt;
            }catch(Throwable failure){throw scope.failure(failure);}
        }
    }
    private Map<String,Object> change(MedicalRecordCorrectionContracts.Command command,MedicalRecord source,MedicalRecordCorrectionRepository.State state,long actor,Instant now,org.familyhealthcare.service.medicalreview.MedicalReviewNativeLifecycle.Scope scope) {
        long version=MedicalRecordCorrectionContracts.id(source.getContentVersion());
        if(version!=command.getExpectedContentVersion())throw versionConflict();
        repository.requireStable(source,state);
        MedicalRecordCorrectionContracts.Snapshot original=repository.snapshot(source.getId());
        if(state!=null&&!state.digest.equals(original.contentDigest()))throw MedicalRecordCorrectionContracts.integrity();
        String validity=state==null?"ACTIVE":state.validity;
        if(command.getOperation()==MedicalRecordCorrectionContracts.Operation.INVALIDATE){
            if("INVALIDATED".equals(validity))throw new WorkflowException(409,"MEDICAL_RECORD_ALREADY_INVALIDATED","This record is already invalidated.");
            validity="INVALIDATED";
        }
        MedicalRecordCorrectionContracts.Snapshot target=target(original,command,now);
        if(command.getOperation()==MedicalRecordCorrectionContracts.Operation.CORRECT&&original.contentDigest().equals(target.contentDigest()))
            throw new WorkflowException(409,"MEDICAL_RECORD_NO_CHANGE","The correction does not change the current content.");
        long sequence=state==null?1:MedicalRecordCorrectionContracts.nextVersion(state.sequence);
        // 基线也必须可完整读取；全部详情预算通过后才写入任何领域状态。
        if(state==null)MedicalRecordRevisionProjection.bounded(0,"BASELINE",version,version,actor,now,"ACTIVE",null,original);
        MedicalRecordRevisionProjection.bounded(sequence,command.getOperation().name(),version,version+1,actor,now,validity,command.getReason(),target);
        scope.preflight(source);
        if(state==null)repository.baseline(source,actor,now,original);
        scope.checkpoint();
        repository.append(source.getId(),sequence,command.getOperation().name(),version,version+1,actor,command.getReason(),now,validity,target);
        scope.checkpoint();repository.persist(source.getId(),version,target,command.getItemPatches());
        scope.checkpoint();
        repository.advance(source.getId(),version,version+1,sequence,validity,target.contentDigest(),now);
        scope.checkpoint();
        // 原生 DATETIME(0) 使用可表示的秒值；核对实际落盘快照，不能散列未保存的微秒。
        if(!target.json().equals(repository.snapshot(source.getId()).json()))throw MedicalRecordCorrectionContracts.integrity();
        authorization.requireLocked(source,actor,false);
        final String digest=target.contentDigest();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){
                if(!digest.equals(repository.snapshot(source.getId()).contentDigest()))throw MedicalRecordCorrectionContracts.integrity();
            }
        });
        scope.retire();
        return MedicalRecordCorrectionContracts.receipt(WorkflowData.map("recordId",source.getId(),"contentVersion",version+1,"historySequence",sequence,"validity",validity,"operation",command.getOperation().name()));
    }
    @SuppressWarnings("unchecked")
    private static MedicalRecordCorrectionContracts.Snapshot target(MedicalRecordCorrectionContracts.Snapshot original,MedicalRecordCorrectionContracts.Command command,Instant now) {
        Map<String,Object> value=WorkflowData.object(original.json());
        Map<String,Object> record=(Map<String,Object>)value.get("record");
        for(MedicalRecordCorrectionContracts.HeaderField field:MedicalRecordCorrectionContracts.HeaderField.values())
            if(command.getHeaderPatch().contains(field))record.put(field.key(),command.getHeaderPatch().get(field));
        Map<Long,Map<String,Object>> items=new HashMap<>();for(Map<String,Object> item:(List<Map<String,Object>>)value.get("items"))items.put((Long)item.get("id"),item);
        for(MedicalRecordCorrectionContracts.ItemPatch patch:command.getItemPatches()){
            Map<String,Object> item=items.get(patch.getItemId());if(item==null)throw MedicalRecordCorrectionContracts.invalid();
            boolean changed=false;
            for(MedicalRecordCorrectionContracts.ItemField field:MedicalRecordCorrectionContracts.ItemField.values())if(patch.contains(field)){
                changed|=!Objects.equals(item.get(field.key()),patch.get(field));item.put(field.key(),patch.get(field));
            }
            Number previous=(Number)item.get("isAbnormal");
            item.put("isAbnormal",patch.reportedFlagFor(changed,previous==null?null:previous.intValue()));
        }
        record.put("contentVersion",MedicalRecordCorrectionContracts.nextVersion(command.getExpectedContentVersion()));record.put("verificationStatus","REVIEW_REQUIRED");
        record.put("verifiedBy",null);record.put("verifiedAt",null);record.put("verificationNote",null);record.put("updatedAt",now.truncatedTo(ChronoUnit.SECONDS).toString());
        return MedicalRecordCorrectionContracts.snapshot(value);
    }
    /** 兼容入口只能写真正未纳管记录，绝不伪造理由/key/版本。调用方必须拥有真实写事务。 */
    public MedicalRecord guardLegacy(long id) {
        repository.requireMutation();
        MedicalRecordCorrectionSchema.Status status=repository.status();
        requireCompatible(status);
        MedicalRecord source=authorization.lockExisting(id,authorization.actor(),false);
        if(source==null)return null;
        if(status==MedicalRecordCorrectionSchema.Status.READY&&(enabled||repository.state(id,true)!=null))throw versionedRequired();
        return source;
    }
    public MedicalRecord guardReview(long id,boolean workspace,boolean approved) {
        repository.requireMutation();
        MedicalRecordCorrectionSchema.Status status=repository.status();requireCompatible(status);
        MedicalRecord source=authorization.lockExisting(id,authorization.actor(),workspace);
        if(source==null)return null;
        if(status==MedicalRecordCorrectionSchema.Status.READY){
            MedicalRecordCorrectionRepository.State state=repository.state(id,true);repository.requireStable(source,state);
            if(state!=null){
                if(approved&&"INVALIDATED".equals(state.validity))throw new WorkflowException(409,"MEDICAL_RECORD_INVALIDATED","An invalidated record cannot be approved.");
                if(!state.digest.equals(repository.snapshot(id).contentDigest()))throw MedicalRecordCorrectionContracts.integrity();
            }
        }
        final long actor=authorization.actor();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){authorization.requireLocked(source,actor,workspace);}
        });
        return source;
    }
    public void recheck(MedicalRecord source,boolean workspace) {authorization.requireLocked(source,authorization.actor(),workspace);}
    public void requireCreateStorage() {requireCompatible(repository.status());}
    private void requireCompatible(MedicalRecordCorrectionSchema.Status status){
        if(status==MedicalRecordCorrectionSchema.Status.INCOMPATIBLE||enabled&&status==MedicalRecordCorrectionSchema.Status.ABSENT)throw MedicalRecordCorrectionSchema.notReady();
    }
    public static WorkflowException versionedRequired(){return new WorkflowException(409,"VERSIONED_COMMAND_REQUIRED","Use an explicit versioned correction or invalidation command for this record.");}
    private static WorkflowException versionConflict(){return new WorkflowException(409,"MEDICAL_RECORD_VERSION_CONFLICT","This record changed. Reload its current version before deciding again.");}
}
