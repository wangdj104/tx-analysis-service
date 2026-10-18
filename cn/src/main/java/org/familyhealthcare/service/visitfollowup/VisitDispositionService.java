package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpRepository.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;

/** 摘要保存/发布走A；专业决定只走计划先锁的V。这里不执行护理计划命令。 */
@Service
public class VisitDispositionService {
    private final VisitFollowUpRepository repo;
    private final VisitFollowUpAdmission admission;
    private final VisitFollowUpProperties properties;
    public VisitDispositionService(VisitFollowUpRepository repo,VisitFollowUpAdmission admission,VisitFollowUpProperties properties) {
        this.repo=Objects.requireNonNull(repo);this.admission=Objects.requireNonNull(admission);this.properties=Objects.requireNonNull(properties);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,String rawJson) {
        return command(actor,parseJson(rawJson,properties.getClock()));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,Command c) {
        Objects.requireNonNull(c);
        if(!c.getOperation().isVisitOperation())throw invalid("A visit follow-up command is required.");
        if(c.isVisitBinding()) {
            VisitFollowUpAdmission.VisitBindingContext context=admission.lockVisitBinding(actor,c);
            return VisitFollowUpCommandStore.executeVisitBinding(actor,c,context,()->decide(c,context));
        }
        VisitFollowUpAdmission.Context context=admission.lockAppointmentOrVisitSource(actor,c);
        return VisitFollowUpCommandStore.executeAppointment(actor,c,context,()->save(c,context));
    }
    private Map<String,Object> save(Command c,VisitFollowUpAdmission.Context context) {
        if(c.getOperation()==Operation.CREATE_VISIT) {
            // 再核真实发生时间，随后才消费仅本事务可用的原生创建凭证。
            ActualTime actual=actual(object(c.getBody().get("actual")),properties.getClock());
            if(actual.getTimeKind()!=TimeKind.KNOWN)throw invalid("Enter the real visit time and offset before saving a new summary.");
            Map<String,Object> origin=object(c.getBody().get("originAppointment"));
            if(origin!=null) {
                SourceRef ref=new SourceRef(SourceKind.valueOf((String)origin.get("sourceKind")),id(origin.get("sourceId")));
                NativeSource nativeOrigin=repo.source(ref);Map<String,Object> prior=repo.state(ref);
                // 只关联已明确纳管的当前来源；不能把未纳管/已变化的预约当作创建凭证。
                if(prior==null||nativeOrigin.getPatientId()!=c.getPatientId()||number(prior.get("lock_version"))!=number(origin.get("expectedVersion"))
                        ||!nativeOrigin.getFingerprint().equals(origin.get("expectedSourceFingerprint"))
                        ||!nativeOrigin.getFingerprint().equals(prior.get("source_fingerprint")))throw changed();
            }
            CreatedSource created=repo.insertCreatedSource(context);
            SourceRef source=created.getReference();long state=repo.insertCreatedState(context,created);
            // visit的实际时间不是预约到诊fact，不设置current_fact_event_id。
            Map<String,Object> patch=map("source_revision",1L,"last_native_event_id","SELF","actual_time_kind","KNOWN",
                    "actual_at_utc",actual.getOccurredAt(),"actual_offset",actual.getInputOffset());
            long event=repo.appendAppointmentEvent(context,state,patch,null,null);repo.advanceAppointmentState(context,state,event);
            return result(c,source.getId(),state,event);
        }
        NativeSource source=repo.source(c.getSource());Map<String,Object> state=repo.state(c.getSource());
        if(state==null||!"DRAFT".equals(source.getValues().get("status"))||!"UNASSESSED".equals(state.get("disposition"))
                ||!source.getFingerprint().equals(state.get("source_fingerprint")))throw changed();
        LocalDateTime published=LocalDateTime.ofInstant(properties.now(),ZoneId.systemDefault()).withNano(0);
        NativeSource target=repo.publishedVisitTarget(context,published);
        Map<String,Object> patch=target.nativeTarget();patch.put("source_fingerprint",target.getFingerprint());
        patch.put("source_revision",nextVersion(number(state.get("source_revision"))));patch.put("last_native_event_id","SELF");
        long workflow=id(state.get("id"));long event=repo.appendAppointmentEvent(context,workflow,patch,null,null);
        repo.advanceAppointmentState(context,workflow,event);repo.writePublishedVisit(context,published);
        return result(c,c.getSourceId(),workflow,event);
    }
    private Map<String,Object> decide(Command c,VisitFollowUpAdmission.VisitBindingContext context) {
        NativeSource source=repo.source(c.getSource());Map<String,Object> state=repo.state(c.getSource());
        if(!"PUBLISHED".equals(source.getValues().get("status")))throw changed();
        boolean first=state==null||"UNASSESSED".equals(state.get("disposition"));
        if((c.getOperation()==Operation.SET_DISPOSITION)!=first)throw changed();
        if(state!=null)for(Map.Entry<String,Object> field:source.nativeTarget().entrySet())
            if(!Objects.equals(normalize(field.getValue()),normalize(state.get(field.getKey()))))throw changed();
        long workflow=state==null?repo.insertVisitState(context,null):id(state.get("id"));
        long revision=state==null?1:nextVersion(number(state.get("disposition_revision")));
        Map<String,Object> patch=map("disposition",c.getBody().get("disposition"),"disposition_revision",revision,"decision_event_id","SELF");
        if(state==null)patch.put("source_revision",1L);
        else if(!source.getFingerprint().equals(state.get("source_fingerprint"))) {
            // 原文变化后只能由本次明确决定重新绑定；不改历史，也不猜原actual。
            patch.put("source_fingerprint",source.getFingerprint());patch.put("source_revision",nextVersion(number(state.get("source_revision"))));
        }
        long event=repo.appendBindingEvent(context,workflow,patch,(String)c.getBody().get("reason"),null);
        repo.advanceBindingState(context,workflow,event);
        for(Map<String,Object> link:VisitFollowUpAdmission.requestedLinks(c))repo.insertActionLink(context,workflow,revision,
                id(link.get("planId")),id(link.get("revisionId")),id(link.get("actionId")),event,(String)link.get("kind"));
        return result(c,c.getSourceId(),workflow,event);
    }
    private static Map<String,Object> result(Command c,long source,long state,long event) {
        return map("workflowId",state,"sourceKind","VISIT_RECORD","sourceId",source,"version",nextVersion(c.getExpectedVersion()),"eventId",event,"operation",c.getOperation().name());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return(Map<String,Object>)value;}
    private static WorkflowException changed(){return new WorkflowException(409,"SOURCE_CHANGED","The visit changed. Refresh and compare before making a new decision.");}
}
