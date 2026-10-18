package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 当前内容采用一致快照；独立当前权限在投影前后检查，cursor 从来不是授权凭证。 */
@Service
public class MedicalRecordCorrectionQueryService {
    private final MedicalRecordCorrectionRepository repository;
    private final MedicalRecordCorrectionAuthorization authorization;
    private final boolean enabled;
    @org.springframework.beans.factory.annotation.Autowired
    private org.familyhealthcare.util.DataScopeHelper legacyScope;
    public MedicalRecordCorrectionQueryService(MedicalRecordCorrectionRepository repository,MedicalRecordCorrectionAuthorization authorization,@Value("${medical-record-correction.enabled:false}")boolean enabled){this.repository=repository;this.authorization=authorization;this.enabled=enabled;}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> current(long id,Long patient){
        long actor=authorization.actor();MedicalRecord source=source(id,patient,actor);
        MedicalRecordCorrectionSchema.Status status=readiness();
        MedicalRecordCorrectionRepository.State state=state(source,status);
        MedicalRecordCorrectionContracts.Snapshot snapshot=repository.snapshot(id);
        if(state!=null&&!state.digest.equals(snapshot.contentDigest()))throw MedicalRecordCorrectionContracts.integrity();
        Map<String,Object> result=base(source,state);
        result.put("current",MedicalRecordRevisionProjection.publicSnapshot(snapshot));
        result.putAll(capabilityFlags(source, state, status, actor));
        result.put("readOnlyFields",Arrays.asList("patientId","patientName","userId","recordType","sourceType","sourceExternalId","confidenceScore","createdAt","updatedAt","contentVersion","verificationStatus","verifiedBy","verifiedAt","verificationNote","attachments","aiRawResult","importFacts"));
        authorization.requireFresh(source,actor,false);return result;
    }
    /** 只读按钮提示不读取快照；超大旧记录仍可按原入口编辑，绝不为此放宽快照限额。 */
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> capabilities(long id, Long patient) {
        long actor = authorization.actor();
        MedicalRecord source = source(id, patient, actor);
        MedicalRecordCorrectionSchema.Status status = readiness();
        MedicalRecordCorrectionRepository.State state = status == MedicalRecordCorrectionSchema.Status.READY
                ? repository.state(id, false) : null;
        if (state != null) repository.requireStable(source, state);
        Map<String,Object> result = WorkflowData.map("recordId", id, "patientId", patient, "managed", state != null);
        result.putAll(capabilityFlags(source, state, status, actor));
        authorization.requireFresh(source, actor, false);
        return result;
    }

    private Map<String,Object> capabilityFlags(MedicalRecord source, MedicalRecordCorrectionRepository.State state,
                                                MedicalRecordCorrectionSchema.Status status, long actor) {
        boolean canWrite = authorization.canWrite(source, actor);
        boolean versioned = canWrite && enabled && status == MedicalRecordCorrectionSchema.Status.READY;
        boolean legacy = canWrite && !enabled && state == null && legacyWriteAllowed(source);
        return WorkflowData.map("canCorrect", versioned, "canInvalidate", versioned && (state == null || "ACTIVE".equals(state.validity)),
                "canLegacyEdit", legacy, "canLegacyDelete", legacy);
    }

    /** 原写路径还有独立范围检查；GET 必须显式按 MEDICAL 写权限判断，不能误用只读资格。 */
    private boolean legacyWriteAllowed(MedicalRecord source) {
        if (legacyScope == null) return false;
        try {
            if (source.getPatientId() == null) legacyScope.assertOwnedByCurrentUser(source.getUserId());
            else legacyScope.requirePatientAccess(source.getPatientId(), "MEDICAL", true);
            return true;
        } catch (IllegalStateException denied) {
            return false;
        }
    }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> history(long id,Long patient,int size,String cursor){
        if(size<1||size>50)throw MedicalRecordCorrectionContracts.invalid();
        long actor=authorization.actor();MedicalRecord source=source(id,patient,actor);
        MedicalRecordCorrectionRepository.State state=state(source,readiness());
        long before=cursor==null?MedicalRecordCorrectionContracts.MAX_ID:cursor(cursor,actor,patient,id);
        Map<String,Object> result=base(source,state);
        List<Map<String,Object>> entries=state==null?new ArrayList<>():repository.summaries(id,before,size+1);
        String next=null;if(entries.size()>size){entries=new ArrayList<>(entries.subList(0,size));next=cursor(actor,patient,id,((Number)entries.get(size-1).get("historySequence")).longValue());}
        result.put("entries",entries);result.put("nextCursor",next);
        authorization.requireFresh(source,actor,false);return result;
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> revision(long id,Long patient,long sequence){
        if(sequence<0||sequence>MedicalRecordCorrectionContracts.MAX_ID)throw MedicalRecordCorrectionContracts.invalid();
        long actor=authorization.actor();MedicalRecord source=source(id,patient,actor);
        MedicalRecordCorrectionRepository.State state=state(source,readiness());
        if(state==null||sequence>state.sequence)throw new org.familyhealthcare.service.workflow.WorkflowException(404,"MEDICAL_RECORD_REVISION_UNAVAILABLE","This captured revision is not available.");
        Map<String,Object> result=repository.revision(id,sequence);
        MedicalRecordCorrectionContracts.Snapshot snapshot=(MedicalRecordCorrectionContracts.Snapshot)result.get("snapshot");
        Map<?,?> record=(Map<?,?>)snapshot.asMap().get("record");
        if(!Objects.equals(source.getPatientId(),record.get("patientId"))||!Objects.equals(source.getUserId(),record.get("userId")))throw MedicalRecordCorrectionContracts.integrity();
        result=MedicalRecordRevisionProjection.bounded(result);authorization.requireFresh(source,actor,false);return result;
    }
    private MedicalRecord source(long id,Long patient,long actor){
        MedicalRecordCorrectionContracts.id(id);if(patient!=null)MedicalRecordCorrectionContracts.id(patient);
        MedicalRecord source=repository.binding(id,false);
        if(source==null||!Objects.equals(patient,source.getPatientId()))throw MedicalRecordCorrectionAuthorization.denied();
        authorization.requireFresh(source,actor,false);return source;
    }
    private MedicalRecordCorrectionSchema.Status readiness(){
        MedicalRecordCorrectionSchema.Status status=repository.status();
        if(status==MedicalRecordCorrectionSchema.Status.INCOMPATIBLE||enabled&&status==MedicalRecordCorrectionSchema.Status.ABSENT)throw MedicalRecordCorrectionSchema.notReady();return status;
    }
    private MedicalRecordCorrectionRepository.State state(MedicalRecord source,MedicalRecordCorrectionSchema.Status status){
        MedicalRecordCorrectionRepository.State state=status==MedicalRecordCorrectionSchema.Status.READY?repository.state(source.getId(),false):null;
        repository.requireStable(source,state);return state;
    }
    private static Map<String,Object> base(MedicalRecord source,MedicalRecordCorrectionRepository.State state){
        return WorkflowData.map("recordId",source.getId(),"patientId",source.getPatientId(),"contentVersion",source.getContentVersion(),"managed",state!=null,"validity",state==null?"UNTRACKED":state.validity,"earliestCapturedContentVersion",state==null?null:state.baselineVersion,"earlierHistoryUnavailable",true);
    }
    private static String cursor(long actor,Long patient,long id,long before){return Base64.getUrlEncoder().withoutPadding().encodeToString(("1:"+actor+":"+(patient==null?"null":patient)+":"+id+":"+before).getBytes(StandardCharsets.US_ASCII));}
    private static long cursor(String value,long actor,Long patient,long id){
        if(value.length()>160||!value.matches("[A-Za-z0-9_-]+"))throw MedicalRecordCorrectionContracts.invalid();
        try{String raw=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.US_ASCII);String[] fields=raw.split(":",-1);
            if(fields.length!=5||!fields[4].matches("[1-9][0-9]*"))throw MedicalRecordCorrectionContracts.invalid();
            long before=MedicalRecordCorrectionContracts.id(Long.parseLong(fields[4]));if(!cursor(actor,patient,id,before).equals(value))throw MedicalRecordCorrectionContracts.invalid();return before;
        }catch(IllegalArgumentException failure){throw MedicalRecordCorrectionContracts.invalid();}
    }
}
