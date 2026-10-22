package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.dto.MedicalRecordReviewSummary;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.ClinicalImportFactsRepository;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionRepository;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewApiContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContentFingerprint.integrity;
import static org.familyhealthcare.service.medicalreview.MedicalReviewQueryRepository.*;

/** Dormant same-snapshot source preparation and current-authorized finite history reads. */
@Service
@ConditionalOnProperty(name="medical-review.api-enabled",havingValue="true",matchIfMissing=false)
public class MedicalReviewQueryService {
    private final DataSource dataSource;private final DataSourceTransactionManager transactions;
    private final MedicalRecordValidityProjection validity;private final MedicalReviewQualificationReader qualification;
    private final MedicalRecordCorrectionRepository corrections;private final MedicalRecordReviewAccess access;
    private final ClinicalImportFactsRepository imports;private final MedicalReviewSourceRepository sources;private final boolean enabled;
    public MedicalReviewQueryService(JdbcTemplate jdbc,DataSourceTransactionManager transactions,MedicalReviewSchema schema,
            MedicalRecordValidityProjection validity,MedicalReviewQualificationReader qualification,MedicalRecordCorrectionRepository corrections,
            MedicalRecordReviewAccess access,ClinicalImportFactsRepository imports,@Value("${medical-review.enabled:false}") boolean enabled){
        if(jdbc==null||jdbc.getDataSource()==null||transactions==null||schema==null||validity==null||qualification==null||corrections==null||access==null||imports==null)throw unavailable();
        this.dataSource=jdbc.getDataSource();this.transactions=transactions;this.validity=validity;this.qualification=qualification;this.corrections=corrections;this.access=access;this.imports=imports;this.enabled=enabled;
        sources=new MedicalReviewSourceRepository(jdbc,schema);graph();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,rollbackFor=Exception.class)
    public Map<String,Object> current(long recordId,Long patientId){
        selectors(recordId,patientId,null);try(Guard guard=new Guard()){
            Read read=new Read(guard,recordId,patientId);
            MedicalReviewContentFingerprint.Content content=sources.readSnapshot(read.source);guard.verify();
            Map<String,Object> original=imports.readMedicalOriginal(patientId,recordId);guard.verify();
            Map<String,Object> display=read.repository.display(read.source,content,read.snapshot,original);guard.verify();
            Permissions permissions=permissions(read);MedicalRecordValidityProjection.State current=read.frame();
            Map<String,Object> prepared=map("expectedContentVersion",read.source.getContentVersion(),"contentFingerprint",content.fingerprint(),"initialLegacyFingerprint",read.sourceHead==null?content.initialLegacyFingerprint():null,"lifecycleFingerprint",read.lifecycle.fingerprint());
            Map<String,Object> result=map("contractVersion",1,"recordId",recordId,"patientId",patientId,"sourceRecord",display,
                    "reviewSummary",summary(MedicalRecordReviewSummary.fromObservedState(current)),"newCommandsEnabled",enabled,"preparation",prepared,
                    "channels",map("source",channelView(read,Channel.SOURCE,permissions),"doctor",channelView(read,Channel.DOCTOR,permissions)));
            read.frame();if(!permissions.equals(permissions(read)))throw changed();guard.verify();return result;
        }catch(IllegalStateException failure){throw sanitized(failure);}
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,rollbackFor=Exception.class)
    public Map<String,Object> history(long recordId,Long patientId,Channel channel,int size,String encoded){
        selectors(recordId,patientId,channel);reviewChannel(channel);if(size<1||size>50)throw invalidRequest();Cursor supplied=encoded==null?null:cursor(encoded);
        try(Guard guard=new Guard()){
            Read read=new Read(guard,recordId,patientId);Cursor selected=selectCursor(read,channel,size,supplied);
            List<Map<String,Object>> entries=new ArrayList<>();String next=null;
            if(selected!=null){List<Event> rows=read.repository.page(recordId,selected.generation,channel,selected.upper,size);
                if(rows.isEmpty()||rows.get(0).decision!=selected.upper)throw integrity();
                if(selected.upper<selected.highWater){Event successor=read.repository.event(recordId,selected.generation,channel,selected.upper+1);adjacent(rows.get(0),successor);}
                for(int i=1;i<rows.size();i++)adjacent(rows.get(i),rows.get(i-1));
                int count=Math.min(size,rows.size());for(int i=0;i<count;i++)entries.add(rows.get(i).projection());Event last=rows.get(count-1);
                if(last.decision!=1){if(rows.size()!=size+1)throw integrity();next=selected.withUpper(last.decision-1).encode();}
                else if(rows.size()!=count)throw integrity();
            }
            read.frame();Map<String,Object> result=map("contractVersion",1,"recordId",recordId,"patientId",patientId,"channel",channel.name(),"highWaterDecisionVersion",selected==null?0L:selected.highWater,
                    "entries",entries,"pageCursor",selected==null?null:selected.encode(),"nextCursor",next);
            read.frame();guard.verify();return result;
        }catch(IllegalStateException failure){throw sanitized(failure);}
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,rollbackFor=Exception.class)
    public Map<String,Object> detail(long recordId,Long patientId,Channel channel,long decisionVersion,String encoded){
        selectors(recordId,patientId,channel);reviewChannel(channel);safeId(decisionVersion);Cursor supplied=cursor(encoded);
        try(Guard guard=new Guard()){
            Read read=new Read(guard,recordId,patientId);requireText(read,channel);Cursor selected=selectCursor(read,channel,supplied.size,supplied);
            if(selected==null||decisionVersion>selected.highWater)throw error(404,"MEDICAL_REVIEW_EVENT_UNAVAILABLE");
            Event event=read.repository.event(recordId,selected.generation,channel,decisionVersion);if(event==null)throw integrity();
            Map<String,Object> text=read.repository.text(event);guard.verify();read.frame();
            Map<String,Object> result=map("contractVersion",1,"recordId",recordId,"patientId",patientId,"channel",channel.name(),"event",event.projection(),"text",text);
            read.frame();requireText(read,channel);guard.verify();return result;
        }catch(IllegalStateException failure){throw sanitized(failure);}
    }
    private Map<String,Object> channelView(Read read,Channel channel,Permissions p){Event head=read.head(channel);List<String> operations=new ArrayList<>();
        if(enabled&&(head==null||head.decision<MAX_ID)){
            if(head==null){if(channel==Channel.SOURCE&&p.sourceWrite){operations.add("APPROVE");operations.add("REJECT");}else if(channel==Channel.DOCTOR&&p.doctorSubmit)operations.add("REQUEST");}
            else if(head.state==State.PENDING){if(channel==Channel.SOURCE?p.sourceWrite:p.doctorDecision){operations.add("APPROVE");operations.add("REJECT");}}
            else if(head.cycle<MAX_ID&&(channel==Channel.SOURCE?p.sourceWrite:p.doctorSubmit))operations.add("RESUBMIT");
            if(!read.snapshot.active())operations.removeIf(operation->!"REJECT".equals(operation));
        }
        Cursor initial=head==null?null:newCursor(read,channel,head,20);
        return map("expectedDecisionVersion",head==null?0L:head.decision,"allowedOperations",operations,"canReadText",channel==Channel.SOURCE?p.sourceWrite:p.doctorSubmit||p.doctorDecision,"historyCursor",initial==null?null:initial.encode());
    }
    private Cursor selectCursor(Read read,Channel channel,int size,Cursor supplied){Event head=read.head(channel);
        if(supplied==null)return head==null?null:newCursor(read,channel,head,size);
        if(supplied.actor!=read.guard.actor||supplied.record!=read.source.getId()||!Objects.equals(supplied.patient,read.source.getPatientId())||!Objects.equals(supplied.owner,read.source.getUserId())||supplied.channel!=channel||supplied.size!=size)throw invalidRequest();
        if(!supplied.generation.equals(read.lifecycle.getCurrentGeneration())||supplied.watermark!=read.lifecycle.getLatestDeletionEventId())throw error(409,"MEDICAL_REVIEW_HISTORY_CHANGED");
        if(head==null||supplied.highWater>head.decision)throw integrity();Event anchor=supplied.highWater==head.decision?head:read.repository.event(read.source.getId(),supplied.generation,channel,supplied.highWater);
        if(anchor==null||anchor.id!=supplied.event)throw integrity();return supplied;
    }
    private Cursor newCursor(Read read,Channel channel,Event head,int size){return new Cursor(read.guard.actor,read.source.getId(),read.source.getPatientId(),read.source.getUserId(),channel,read.lifecycle.getCurrentGeneration(),read.lifecycle.getLatestDeletionEventId(),head.decision,head.id,head.decision,size);}
    private Permissions permissions(Read read){boolean write=permission(read,false);boolean doctor=read.source.getPatientId()!=null&&permission(read,true);return new Permissions(write,read.source.getPatientId()!=null&&write,doctor);}
    private boolean permission(Read read,boolean doctor){read.guard.verify();try{access.requireCurrent(read.source,true,doctor);read.guard.verify();return true;}
        catch(IllegalStateException failure){read.guard.verify();if("Access denied to source record.".equals(failure.getMessage()))return false;throw unavailable();}}
    private void requireText(Read read,Channel channel){boolean allowed=permission(read,false);if(channel==Channel.DOCTOR){allowed=read.source.getPatientId()!=null&&(allowed||permission(read,true));}if(!allowed)throw denied();read.guard.verify();}
    private static final class Permissions {
        final boolean sourceWrite,doctorSubmit,doctorDecision;Permissions(boolean w,boolean s,boolean d){sourceWrite=w;doctorSubmit=s;doctorDecision=d;}
        @Override public boolean equals(Object other){if(!(other instanceof Permissions))return false;Permissions p=(Permissions)other;return p.sourceWrite==sourceWrite&&p.doctorSubmit==doctorSubmit&&p.doctorDecision==doctorDecision;}
        @Override public int hashCode(){return Objects.hash(sourceWrite,doctorSubmit,doctorDecision);}
    }
    private final class Read {
        final Guard guard;final MedicalReviewQueryRepository repository;final MedicalRecord source;final MedicalRecordValidityProjection.State snapshot;final Map<Long,MedicalRecordValidityProjection.State> states;
        final Lifecycle lifecycle;final Event sourceHead,doctorHead;
        Read(Guard guard,long id,Long patient){this.guard=guard;repository=new MedicalReviewQueryRepository(guard.connection,guard.holder,guard::verify);
            source=repository.binding(id,patient);guard.verify();access.requireReadAdmission(Collections.singletonList(source),guard.actor);guard.verify();
            states=validity.open().qualifiedSnapshot(Collections.singleton(id));guard.verify();if(states==null||states.size()!=1||!states.containsKey(id))throw changed();snapshot=states.get(id);
            checkState(snapshot,MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT);if(!snapshot.reviewSummaryMetadataAvailable())throw MedicalReviewSchema.notReady();
            lifecycle=sources.readSnapshotLifecycle(source);guard.verify();sourceHead=head(Channel.SOURCE,snapshot.source);doctorHead=head(Channel.DOCTOR,snapshot.doctor);
        }
        private Event head(Channel channel,MedicalReviewQualificationReader.ChannelFacts facts){if(facts==null)return null;Event e=repository.event(source.getId(),lifecycle.getCurrentGeneration(),channel,facts.decisionVersion);if(e==null)throw integrity();e.head(facts);return e;}
        Event head(Channel channel){return channel==Channel.SOURCE?sourceHead:doctorHead;}
        MedicalRecordValidityProjection.State frame(){guard.verify();Map<Long,MedicalRecordValidityProjection.State> frame=access.currentReadFrame(Collections.singletonList(source),states,guard.actor);guard.verify();
            if(frame==null||frame.size()!=1||!frame.containsKey(source.getId()))throw changed();MedicalRecordValidityProjection.State state=frame.get(source.getId());checkState(state,MedicalReviewQualificationReader.Semantics.CURRENT_CHECK);
            if(!snapshot.signature.equals(state.signature))throw changed();return state;}
        void checkState(MedicalRecordValidityProjection.State state,MedicalReviewQualificationReader.Semantics semantics){
            if(state==null||!state.reviewObserved||state.qualificationSemantics!=semantics||state.checkedAt==null||state.signature==null||state.signature.isEmpty()||state.id!=source.getId()
                    ||!Objects.equals(state.patientId,source.getPatientId())||!Objects.equals(state.ownerId,source.getUserId())||!Objects.equals(state.version,source.getContentVersion())||!Objects.equals(state.review,source.getVerificationStatus()))throw changed();
        }
    }
    private static void selectors(long id,Long patient,Channel channel){safeId(id);if(patient!=null)safeId(patient);if(channel==Channel.DOCTOR&&patient==null)throw invalidRequest();}
    private static WorkflowException changed(){return error(409,"CURRENT_RESULT_UNAVAILABLE");}
    private static WorkflowException sanitized(IllegalStateException failure){return "Access denied to source record.".equals(failure.getMessage())?denied():unavailable();}
    private void graph(){try{
        if(transactions.getDataSource()!=dataSource||!AopUtils.isAopProxy(access)||AopUtils.getTargetClass(access)!=MedicalRecordReviewAccess.class)throw unavailable();
        access.requireDataSource(dataSource);validity.requireDataSource(dataSource);qualification.requireDataSource(dataSource);corrections.requireDataSource(dataSource);imports.requireDataSource(dataSource);
    }catch(IllegalStateException bad){throw unavailable();}}
    private final class Guard implements AutoCloseable {
        final Thread thread=Thread.currentThread();final long actor;final ConnectionHolder holder;final SimpleConnectionHandle handle;final Connection connection;private Connection borrowed;
        Guard(){Long user=CurrentUserUtil.getCurrentUserId();if(user==null||user<1||user>MAX_ID)throw error(401,"AUTHENTICATION_REQUIRED");actor=user;graph();
            Object resource=TransactionSynchronizationManager.getResource(dataSource);if(resource==null||resource.getClass()!=ConnectionHolder.class)throw unavailable();holder=(ConnectionHolder)resource;
            if(holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw unavailable();handle=(SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();verify();
            Connection acquired=null;try{acquired=DataSourceUtils.getConnection(dataSource);if(acquired!=connection||!DataSourceUtils.isConnectionTransactional(acquired,dataSource))throw unavailable();verify();borrowed=acquired;}
            finally{if(borrowed==null&&acquired!=null)DataSourceUtils.releaseConnection(acquired,dataSource);}
        }
        void verify(){graph();if(!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId()))throw denied();
            if(Thread.currentThread()!=thread||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                    ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    ||TransactionSynchronizationManager.getResource(dataSource)!=holder||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw unavailable();
            try{if(connection==null||holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()||connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)throw unavailable();}
            catch(SQLException|IllegalStateException bad){throw unavailable();}
        }
        @Override public void close(){if(borrowed!=null){Connection value=borrowed;borrowed=null;DataSourceUtils.releaseConnection(value,dataSource);}}
    }
}
