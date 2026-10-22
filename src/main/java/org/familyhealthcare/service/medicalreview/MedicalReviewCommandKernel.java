package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionAuthorization;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionRepository;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.aop.support.AopUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContentFingerprint.integrity;
import static org.familyhealthcare.service.medicalreview.MedicalReviewCommandRepository.*;

/** Unregistered finite command core. No product caller or transaction owner exists in this slice. */
final class MedicalReviewCommandKernel {
    private final DataSource dataSource;private final MedicalReviewSchema schema;
    private final MedicalRecordCorrectionSchema correctionSchema;private final MedicalRecordCorrectionRepository corrections;
    private final MedicalReviewReceiptAdmission admission;private final MedicalReviewCommandAuthority authority;
    private final MedicalReviewSourceRepository sources;private final MedicalReviewCommandRepository repository=new MedicalReviewCommandRepository();
    private final MedicalReviewReceiptRepository receipts=new MedicalReviewReceiptRepository();
    private final boolean enabled,correctionEnabled;

    MedicalReviewCommandKernel(JdbcTemplate jdbc,MedicalReviewSchema schema,MedicalRecordCorrectionSchema correctionSchema,
                               MedicalRecordCorrectionRepository corrections,MedicalRecordReviewAccess access,
                               MedicalReviewReceiptAdmission admission,boolean enabled,boolean correctionEnabled){
        if(jdbc==null||jdbc.getDataSource()==null||schema==null||correctionSchema==null||corrections==null||access==null||admission==null)throw MedicalReviewSchema.notReady();
        dataSource=jdbc.getDataSource();this.schema=schema;this.correctionSchema=correctionSchema;this.corrections=corrections;
        this.admission=admission;this.enabled=enabled;this.correctionEnabled=correctionEnabled;
        authority=new MedicalReviewCommandAuthority(jdbc,access);sources=new MedicalReviewSourceRepository(jdbc,schema);
    }
    MedicalReviewReceipt execute(Command command){
        if(command==null)throw new WorkflowException(400,"MEDICAL_REVIEW_INVALID_REQUEST","The medical review request is invalid.");
        MedicalReviewCommandAuthority.requireActor(command.getActorId());requireGraph();
        BoundConnection bound=null;
        try{
            bound=new BoundConnection();
            try(BoundConnection owned=bound){
            BindingObservation before=repository.observe(bound.connection,command.getRecordId(),false);
            Optional<MedicalReviewReceipt> completed=admission.recoverCompleted(command);
            // HIT is terminal: the observation has no authority and creates no callback.
            if(completed.isPresent())return completed.get();
            bound.verify();MedicalReviewCommandAuthority.requireActor(command.getActorId());
            BindingObservation binding=repository.observe(bound.connection,command.getRecordId(),true);
            binding.requireIdentity(command.getRecordId(),true);before.requireIdentity(command.getRecordId(),false);
            if(!before.sameIdentity(binding))throw MedicalRecordCorrectionAuthorization.denied();
            schema.requireNewCommands(schema.inspectStorage(bound.connection),enabled);
            MedicalRecord source=binding.source();
            String kind=authority.require(bound.connection,source,command.getActorId(),command.getChannel(),command.getOperation());
            MedicalRecordCorrectionSchema.Status parent9=parent9Status(bound.connection);
            MedicalRecordCorrectionRepository.State correction=parent9(source,parent9,command.getOperation());
            Lifecycle lifecycle=sources.readLockedLifecycle(source);
            CurrentPair pair=repository.currentPair(bound.connection,binding,lifecycle);
            requireLifecycle(command,lifecycle);
            if(command.getExpectedContentVersion()!=binding.version)throw conflict("CONTENT_CONFLICT");
            CurrentState selected=pair.selected(command.getChannel());
            if(command.getExpectedDecisionVersion()!=(selected==null?0:selected.decision))throw conflict("DECISION_CONFLICT");
            Transition transition=transition(command.getChannel(),command.getOperation(),selected,binding.version);
            MedicalReviewContentFingerprint.Content content=sources.readLocked(source);
            if(!command.getContentFingerprint().equals(content.fingerprint()))throw conflict("CONTENT_CONFLICT");
            String legacy=command.getChannel()==Channel.SOURCE&&selected==null?content.initialLegacyFingerprint():null;
            if(legacy!=null&&!legacy.equals(command.getInitialLegacyFingerprint()))throw conflict("LEGACY_CONFLICT");
            String generation=repository.generation(bound.connection,binding,pair,lifecycle),time=repository.now(bound.connection);
            bound.verify();MedicalReviewCommandAuthority.requireActor(command.getActorId());
            if(!kind.equals(authority.require(bound.connection,source,command.getActorId(),command.getChannel(),command.getOperation())))throw MedicalRecordCorrectionAuthorization.denied();
            // Set BEFORE entering even prepareStatement: uncertain mutation is still a mutation attempt.
            bound.mutationAttempted=true;
            MedicalReviewReceipt receipt=repository.persist(bound.connection,command,binding,pair,transition,generation,kind,time,lifecycle.getLatestDeletionEventId());
            CurrentPair post=repository.postPair(pair,binding,receipt);
            Lifecycle after=lifecycle(binding.id,post.source==null?null:generation,post.doctor==null?null:generation,lifecycle.getLatestDeletionEventId());
            ExpectedCommit expected=new ExpectedCommit(binding,post,after,receipt,new TextDescriptor(command.getOperation(),command.getText()),content.fingerprint(),legacy,parent9,correction);
            verifyCompleted(bound,expected);
            register(bound,expected);
            return receipt;
            }
        }catch(SQLException|DataAccessException failure){poison(bound);throw MedicalReviewSchema.notReady();}
        catch(RuntimeException|Error failure){poison(bound);throw failure;}
    }
    private void requireGraph(){
        admission.requireDataSource(dataSource);authority.requireDataSource(dataSource);
        try{corrections.requireDataSource(dataSource);}catch(IllegalStateException failure){throw MedicalReviewSchema.notReady();}
        if(!AopUtils.isAopProxy(admission)||AopUtils.getTargetClass(admission)!=MedicalReviewReceiptAdmission.class)throw MedicalReviewSchema.notReady();
    }
    private MedicalRecordCorrectionSchema.Status parent9Status(Connection c){
        MedicalRecordCorrectionSchema.Status status=correctionSchema.inspectStorage(c);
        if(status==MedicalRecordCorrectionSchema.Status.INCOMPATIBLE||(status==MedicalRecordCorrectionSchema.Status.ABSENT&&correctionEnabled))throw MedicalRecordCorrectionSchema.notReady();
        return status;
    }
    private MedicalRecordCorrectionRepository.State parent9(MedicalRecord source,MedicalRecordCorrectionSchema.Status status,Operation operation){
        if(status!=MedicalRecordCorrectionSchema.Status.READY)return null;
        MedicalRecordCorrectionRepository.State state=corrections.state(source.getId(),true);
        corrections.requireStable(source,state);
        if(state!=null){
            if(!state.digest.equals(corrections.snapshot(source.getId()).contentDigest()))throw MedicalRecordCorrectionContracts.integrity();
            if("INVALIDATED".equals(state.validity)&&operation!=Operation.REJECT)throw new WorkflowException(409,"MEDICAL_RECORD_INVALIDATED","An invalidated record cannot be approved.");
        }
        return state;
    }
    private void verifyCompleted(BoundConnection bound,ExpectedCommit expected)throws SQLException{
        bound.verify();requireGraph();MedicalReviewReceipt receipt=expected.receipt;MedicalReviewCommandAuthority.requireActor(receipt.actorId);
        BindingObservation actual=repository.observe(bound.connection,receipt.recordId,true);actual.requireIdentity(receipt.recordId,true);expected.binding.requireSame(actual);
        MedicalRecord source=actual.source();
        if(!receipt.actorKind.equals(authority.require(bound.connection,source,receipt.actorId,receipt.channel,receipt.operation)))throw MedicalRecordCorrectionAuthorization.denied();
        MedicalRecordCorrectionSchema.Status parent9=parent9Status(bound.connection);
        if(parent9!=expected.parent9)throw MedicalRecordCorrectionContracts.integrity();
        MedicalRecordCorrectionRepository.State correction=parent9(source,parent9,receipt.operation);
        requireSameCorrection(expected.correction,correction);
        Lifecycle lifecycle=sources.readLockedLifecycle(source);
        if(!Objects.equals(expected.lifecycle.getCurrentGeneration(),lifecycle.getCurrentGeneration())||expected.lifecycle.getLatestDeletionEventId()!=lifecycle.getLatestDeletionEventId())throw integrity();
        if(!expected.pair.equals(repository.currentPair(bound.connection,actual,lifecycle)))throw integrity();
        MedicalReviewContentFingerprint.Content content=sources.readLocked(source);
        if(!expected.contentFingerprint.equals(content.fingerprint())||(expected.legacyFingerprint!=null&&!expected.legacyFingerprint.equals(content.initialLegacyFingerprint())))throw integrity();
        receipts.requireUnchangedCompleted(bound.connection,receipt);repository.requireText(bound.connection,receipt,expected.text);
        if(!receipt.actorKind.equals(authority.require(bound.connection,source,receipt.actorId,receipt.channel,receipt.operation)))throw MedicalRecordCorrectionAuthorization.denied();
        bound.verify();MedicalReviewCommandAuthority.requireActor(receipt.actorId);
    }
    private static void requireSameCorrection(MedicalRecordCorrectionRepository.State a,MedicalRecordCorrectionRepository.State b){
        if(a==null||b==null){if(a!=b)throw MedicalRecordCorrectionContracts.integrity();return;}
        if(!Objects.equals(a.patientId,b.patientId)||!Objects.equals(a.ownerId,b.ownerId)||a.baselineVersion!=b.baselineVersion||a.version!=b.version||a.sequence!=b.sequence||!a.validity.equals(b.validity)||!a.digest.equals(b.digest))throw MedicalRecordCorrectionContracts.integrity();
    }
    private void register(final BoundConnection bound,final ExpectedCommit expected){
        // Captures only scalar completion facts/digests, never Command or free text.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){
                try{verifyCompleted(bound,expected);}
                catch(SQLException|DataAccessException failure){bound.holder.setRollbackOnly();throw MedicalReviewSchema.notReady();}
                catch(RuntimeException|Error failure){bound.holder.setRollbackOnly();throw failure;}
            }
        });
    }
    private static void poison(BoundConnection bound){if(bound!=null&&bound.mutationAttempted)bound.holder.setRollbackOnly();}
    private final class BoundConnection implements AutoCloseable{
        final ConnectionHolder holder;final SimpleConnectionHandle handle;final Connection connection;private Connection borrowed;boolean mutationAttempted;
        BoundConnection()throws SQLException{
            mode();Object resource=TransactionSynchronizationManager.getResource(dataSource);
            if(resource==null||resource.getClass()!=ConnectionHolder.class)throw MedicalReviewSchema.notReady();holder=(ConnectionHolder)resource;
            if(!holder.isSynchronizedWithTransaction()||holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw MedicalReviewSchema.notReady();
            handle=(SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();verify();
            Connection acquired=null;
            try{acquired=DataSourceUtils.getConnection(dataSource);if(acquired!=connection||!DataSourceUtils.isConnectionTransactional(acquired,dataSource))throw MedicalReviewSchema.notReady();verify();borrowed=acquired;}
            finally{if(borrowed==null&&acquired!=null)DataSourceUtils.releaseConnection(acquired,dataSource);}
        }
        void verify()throws SQLException{
            mode();if(TransactionSynchronizationManager.getResource(dataSource)!=holder||!holder.isSynchronizedWithTransaction()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw MedicalReviewSchema.notReady();
            try{if(holder.getConnection()!=connection)throw MedicalReviewSchema.notReady();}catch(IllegalStateException|IllegalArgumentException failure){throw MedicalReviewSchema.notReady();}
            if(connection==null||connection.getAutoCommit()||connection.isReadOnly()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw MedicalReviewSchema.notReady();
        }
        @Override public void close(){
            if(borrowed!=null){Connection release=borrowed;borrowed=null;
                // A swapped resource must not cause DataSourceUtils to close the original transaction connection.
                if(TransactionSynchronizationManager.getResource(dataSource)==holder)DataSourceUtils.releaseConnection(release,dataSource);
                else holder.released();
            }
        }
    }
    private static void mode(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw MedicalReviewSchema.notReady();}
}
