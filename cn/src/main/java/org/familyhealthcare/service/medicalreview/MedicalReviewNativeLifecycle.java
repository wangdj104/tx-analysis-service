package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.entity.MedicalRecordAttachment;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionAuthorization;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionRepository;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema;
import org.familyhealthcare.service.workflow.HouseholdAuthorityLocks;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.*;
import java.math.BigDecimal;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewNativeRepository.*;

/** Safety hooks for existing native owners. Does not activate or call the review command kernel. */
@Service
public class MedicalReviewNativeLifecycle {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final MedicalReviewSchema schema;
    private final MedicalRecordCorrectionSchema correctionSchema;
    private final MedicalRecordCorrectionRepository corrections;
    private final MedicalRecordReviewAccess access;
    private final HouseholdAuthorityLocks locks;
    private final DataScopeHelper dataScope;
    private final MedicalReviewSourceRepository sources;
    private final MedicalReviewNativeRepository repository=new MedicalReviewNativeRepository();

    public MedicalReviewNativeLifecycle(JdbcTemplate jdbc,MedicalReviewSchema schema,MedicalRecordCorrectionSchema correctionSchema,
            MedicalRecordCorrectionRepository corrections,MedicalRecordReviewAccess access,HouseholdAuthorityLocks locks,DataScopeHelper dataScope) {
        if(jdbc==null||jdbc.getDataSource()==null||schema==null||correctionSchema==null||corrections==null||access==null||locks==null||dataScope==null)throw MedicalReviewSchema.notReady();
        this.jdbc=jdbc;dataSource=jdbc.getDataSource();this.schema=schema;this.correctionSchema=correctionSchema;this.corrections=corrections;this.access=access;this.locks=locks;this.dataScope=dataScope;
        sources=new MedicalReviewSourceRepository(jdbc,schema);
    }
    private enum Kind { FULL_EDIT, GENERIC_UPDATE, LEGACY_REVIEW, WORKSPACE_REVIEW, CORRECTION, DELETE }
    public Scope openFullEdit(){return open(Kind.FULL_EDIT);}
    public Scope openGenericUpdate(){return open(Kind.GENERIC_UPDATE);}
    public Scope openLegacyReview(){return open(Kind.LEGACY_REVIEW);}
    public Scope openWorkspaceReview(){return open(Kind.WORKSPACE_REVIEW);}
    public Scope openCorrection(){return open(Kind.CORRECTION);}
    public Scope openDelete(){return open(Kind.DELETE);}
    private Scope open(Kind kind){try{return new Scope(kind);}catch(SQLException|DataAccessException failure){throw MedicalReviewSchema.notReady();}}
    private void graph(){try{if(jdbc.getDataSource()!=dataSource)throw MedicalReviewSchema.notReady();corrections.requireDataSource(dataSource);access.requireDataSource(dataSource);locks.requireDataSource(dataSource);}catch(IllegalStateException failure){throw MedicalReviewSchema.notReady();}}
    private static void actor(long actor){if(!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId()))throw MedicalRecordCorrectionAuthorization.denied();}
    private static void mode(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw MedicalReviewSchema.notReady();}

    /** One invocation, one eager resource, and one frozen binding. No SQL/callback or connection is exposed. */
    public final class Scope implements AutoCloseable {
        private final Kind kind; private final Thread thread=Thread.currentThread();
        private final ConnectionHolder holder; private final SimpleConnectionHandle handle; private final Connection connection;
        private final long actor;
        private BindingObservation binding,expectedBinding;
        private MedicalReviewSchema.Status status;
        private MedicalRecordCorrectionSchema.Status parent9;
        private MedicalRecordCorrectionRepository.State correction;
        private Proof before; private CurrentPair expectedPair; private Event expectedWatermark,closure;
        private List<Event> events=Collections.emptyList();
        private String actorKind,contentFingerprint; private Long patientOwner;
        private Counts counts; private EditIntent edit;
        private boolean mutationAttempted,prepared,retired,finished,closed,commitProved,itemsDeleted,attachmentsDeleted,parent9Completed;

        private Scope(Kind kind)throws SQLException {
            this.kind=kind;graph();mode();
            Object resource=TransactionSynchronizationManager.getResource(dataSource);
            if(resource==null||resource.getClass()!=ConnectionHolder.class)throw MedicalReviewSchema.notReady();
            holder=(ConnectionHolder)resource;
            if(!holder.isSynchronizedWithTransaction()||holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw MedicalReviewSchema.notReady();
            handle=(SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();
            Long current=CurrentUserUtil.getCurrentUserId();if(current==null)throw MedicalRecordCorrectionAuthorization.denied();
            actor=MedicalRecordCorrectionContracts.id(current);verifyResource();
            // Installed before any owner admission or reservation: an earlier verifier may abort traversal.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){if(mutationAttempted&&!finished){holder.setRollbackOnly();throw integrity();}}
                @Override public void beforeCompletion(){if(mutationAttempted&&!commitProved)holder.setRollbackOnly();}
            });
        }
        private void verifyResource()throws SQLException {
            mode();graph();actor(actor);
            if(Thread.currentThread()!=thread||TransactionSynchronizationManager.getResource(dataSource)!=holder||!holder.isSynchronizedWithTransaction()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw MedicalReviewSchema.notReady();
            try{if(holder.getConnection()!=connection)throw MedicalReviewSchema.notReady();}catch(IllegalStateException|IllegalArgumentException failure){throw MedicalReviewSchema.notReady();}
            if(connection==null||connection.getAutoCommit()||connection.isReadOnly()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw MedicalReviewSchema.notReady();
        }
        public void checkpoint(){try{active();verifyResource();}catch(Throwable failure){throw failure(failure);}}
        private void active(){if(closed||finished)throw integrity();}
        /** Correction replay retains current authority/resource checks, but never qualifies new review state. */
        public void admitCorrection(MedicalRecord source){
            try{active();if(kind!=Kind.CORRECTION||binding!=null)throw integrity();verifyResource();binding=observe(source);expectedBinding=binding;requireAuthority(false);verifyResource();}
            catch(Throwable failure){throw failure(failure);}
        }
        public void preflight(MedicalRecord source){
            try{
                active();if(prepared)throw integrity();verifyResource();
                BindingObservation observed=observe(source);
                if(binding!=null)binding.requireSame(observed);else binding=observed;
                expectedBinding=binding;requireAuthority(false);
                parent9=correctionSchema.inspectStorage(connection);if(parent9==MedicalRecordCorrectionSchema.Status.INCOMPATIBLE)throw MedicalRecordCorrectionSchema.notReady();
                status=schema.inspectStorage(connection);if(status==MedicalReviewSchema.Status.INCOMPATIBLE)throw MedicalReviewSchema.notReady();
                if(status==MedicalReviewSchema.Status.READY){
                    Lifecycle lifecycle=sources.readInspectedLockedLifecycle(binding.source(),connection,status);verifyResource();
                    before=repository.proof(connection,binding,lifecycle);expectedPair=before.pair;expectedWatermark=before.watermark;
                    if(participating()) {
                        if(kind==Kind.GENERIC_UPDATE)throw conflict("VERSIONED_EDIT_REQUIRED","Use a version-advancing medical-record edit for this record.");
                        if(kind==Kind.LEGACY_REVIEW||kind==Kind.WORKSPACE_REVIEW)throw conflict("WORKFLOW_REQUIRED","Use the medical review workflow for this record.");
                        if(kind==Kind.FULL_EDIT||kind==Kind.CORRECTION){
                            if(binding.version==MAX_ID)throw exhausted();
                            for(CurrentState state:new CurrentState[]{before.pair.doctor,before.pair.source})if(state!=null&&state.decision==MAX_ID)throw exhausted();
                        }
                    }
                }
                Authority classification=classify();actorKind=classification.kind;patientOwner=classification.patientOwner;
                if(kind==Kind.DELETE)counts=repository.counts(connection,binding.id,before,status==MedicalReviewSchema.Status.READY);
                if(kind!=Kind.CORRECTION)correction=readCorrection();
                prepared=true;verifyResource();
            }catch(Throwable failure){throw failure(failure);}
        }
        public boolean participating(){return before!=null&&before.pair.size()!=0;}
        private BindingObservation observe(MedicalRecord source)throws SQLException {
            if(source==null)throw MedicalRecordCorrectionAuthorization.denied();
            BindingObservation value=repository.observe(connection,MedicalRecordCorrectionContracts.id(source.getId()),true);
            value.requireIdentity(source.getId(),true);
            if(!Objects.equals(value.patient,source.getPatientId())||!Objects.equals(value.owner,source.getUserId()))throw MedicalRecordCorrectionAuthorization.denied();
            if(!Objects.equals(value.version,source.getContentVersion()))throw integrity();return value;
        }
        public void prepareEdit(MedicalRecord normalized,List<MedicalRecordItem> items,List<MedicalRecordAttachment> attachments){
            try{active();verifyResource();if(kind!=Kind.FULL_EDIT||!prepared||!participating()||edit!=null||mutationAttempted)throw integrity();
                edit=new EditIntent(sources.readLocked(binding.source()),normalized,items,attachments);verifyResource();
            }catch(Throwable failure){throw failure(failure);}
        }
        /** Must precede entering the primitive, including the correction reservation attempt. */
        public void arm(){try{active();verifyResource();if(binding==null||kind!=Kind.CORRECTION&&!prepared)throw integrity();mutationAttempted=true;}catch(Throwable failure){throw failure(failure);}}
        public void requireHeaderCount(int count){try{checkpoint();if(kind!=Kind.FULL_EDIT||edit==null||count!=1)throw integrity();}catch(Throwable failure){throw failure(failure);}}
        public void deletedItems(int count){try{checkpoint();if(itemsDeleted)throw integrity();long expected=kind==Kind.DELETE?counts.items:edit.oldItems;
                if(count<0||(long)count!=expected)throw integrity();itemsDeleted=true;}catch(Throwable failure){throw failure(failure);}}
        public void deletedAttachments(int count){try{checkpoint();if(attachmentsDeleted)throw integrity();long expected=kind==Kind.DELETE?counts.attachments:edit.removedAttachments;
                if(count<0||(long)count!=expected)throw integrity();attachmentsDeleted=true;}catch(Throwable failure){throw failure(failure);}}
        public void insertedItem(int index,Long id,int count){try{checkpoint();edit.inserted(false,index,id,count);}catch(Throwable failure){throw failure(failure);}}
        public void insertedAttachment(int index,Long id,int count){try{checkpoint();edit.inserted(true,index,id,count);}catch(Throwable failure){throw failure(failure);}}
        public void retire(){
            try{
                active();verifyResource();if(!prepared||retired||!mutationAttempted||(kind!=Kind.FULL_EDIT&&kind!=Kind.CORRECTION))throw integrity();
                BindingObservation after=repository.observe(connection,binding.id,true);after.requireIdentity(binding.id,true);
                if(!binding.sameIdentity(after)||after.version!=binding.version+1)throw integrity();expectedBinding=after;
                if(participating()) {
                    MedicalReviewContentFingerprint.Content content=sources.readLocked(after.source());verifyResource();
                    if(kind==Kind.FULL_EDIT){if(edit==null||!itemsDeleted||!attachmentsDeleted)throw integrity();edit.requireResult(content);edit=null;}
                    repository.requireReset(connection,binding.id);
                    Retirement result=repository.retire(connection,binding,before,actor,actorKind,repository.now(connection));
                    expectedPair=result.pair;events=result.events;contentFingerprint=content.fingerprint();
                }
                retired=true;verifyResource();
            }catch(Throwable failure){throw failure(failure);}
        }
        public void eraseReview(){
            try{
                active();verifyResource();if(kind!=Kind.DELETE||!prepared||mutationAttempted)throw integrity();arm();
                if(status==MedicalReviewSchema.Status.READY){
                    closure=repository.closeGeneration(connection,binding,before,actor,actorKind,repository.now(connection));
                    if(closure!=null){expectedWatermark=closure;events=Collections.singletonList(closure);}
                    repository.erase(connection,binding.id,counts);expectedPair=new CurrentPair(null,null);
                }
                verifyResource();
            }catch(Throwable failure){throw failure(failure);}
        }
        public void deletedNative(int count){try{checkpoint();if(kind!=Kind.DELETE||!itemsDeleted||!attachmentsDeleted||count!=1)throw integrity();}catch(Throwable failure){throw failure(failure);}}
        /** NEW-only immediate receipt/history proof. The existing executor owns its identical beforeCommit check. */
        public void completeCorrection(MedicalRecordCorrectionContracts.Command command,Map<String,Object> receipt){
            try {
                active();verifyResource();if(kind!=Kind.CORRECTION||binding==null||parent9Completed)throw integrity();
                if(prepared){
                    if(command==null||command.getRecordId()!=binding.id||command.getExpectedContentVersion()!=binding.version)throw integrity();
                    MedicalRecordCorrectionRepository.requireCompletedTransition(jdbc,actor,command.getCommandKey(),command,receipt);
                    verifyResource();parent9Completed=true;
                }
                // Do not retain Command, reason, header/item patches, or a mutable receipt in this synchronization.
                complete();
            }catch(Throwable failure){throw failure(failure);}
        }
        /** Last owner step, after its continuation and synchronization registration. */
        public void complete(){
            try{
                active();verifyResource();if(!mutationAttempted||binding==null)throw integrity();
                if(kind==Kind.CORRECTION&&!prepared){verifyReplay();}
                else {
                    if(!prepared||kind==Kind.CORRECTION&&!parent9Completed)throw integrity();
                    if((kind==Kind.FULL_EDIT||kind==Kind.CORRECTION)&&!retired) {
                        // The old unparticipating full-edit false result retains its existing semantics.
                        if(kind!=Kind.FULL_EDIT||participating())throw integrity();
                    }
                    if(kind!=Kind.DELETE) {
                        BindingObservation actual=repository.observe(connection,binding.id,true);actual.requireIdentity(binding.id,true);
                        if(!binding.sameIdentity(actual))throw MedicalRecordCorrectionAuthorization.denied();
                        if(kind==Kind.GENERIC_UPDATE||kind==Kind.LEGACY_REVIEW||kind==Kind.WORKSPACE_REVIEW||!retired)expectedBinding=actual;
                    }
                    if(kind==Kind.CORRECTION)correction=readCorrection();else sameCorrection(correction,readCorrection());
                    verifyCompleted(false);
                }
                finished=true;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void beforeCommit(boolean readOnly){
                        try{if(kind==Kind.CORRECTION&&!prepared)verifyReplay();else verifyCompleted(true);commitProved=true;}
                        catch(Throwable failure){throw failure(failure);}
                    }
                });
                verifyResource();
            }catch(Throwable failure){throw failure(failure);}
        }
        private MedicalRecordCorrectionRepository.State readCorrection(){
            if(parent9!=MedicalRecordCorrectionSchema.Status.READY)return null;
            MedicalRecordCorrectionRepository.State state=corrections.state(binding.id,false);
            if(kind==Kind.DELETE){if(state!=null)throw MedicalRecordCorrectionContracts.integrity();return null;}
            corrections.requireStable(expectedBinding.source(),state);
            if(state!=null&&!state.digest.equals(corrections.snapshot(binding.id).contentDigest()))throw MedicalRecordCorrectionContracts.integrity();
            if((kind==Kind.FULL_EDIT||kind==Kind.GENERIC_UPDATE)&&state!=null)throw MedicalRecordCorrectionContracts.integrity();
            return state;
        }
        private void verifyReplay()throws SQLException {
            verifyResource();BindingObservation now=repository.observe(connection,binding.id,true);now.requireIdentity(binding.id,true);binding.requireSame(now);requireAuthority(false);verifyResource();
        }
        private void verifyCompleted(boolean committing)throws SQLException {
            verifyResource();
            // Admission and beforeCommit inspect both complete schemas. The
            // immediate owner proof retains all row/binding/authority checks;
            // the transaction cannot publish until the final inspection passes.
            if(committing) {
                if(schema.inspectStorage(connection)!=status)throw MedicalReviewSchema.notReady();
                if(correctionSchema.inspectStorage(connection)!=parent9)throw MedicalRecordCorrectionSchema.notReady();
            }
            if(kind==Kind.DELETE) {
                if(repository.observe(connection,binding.id,true).count!=0)throw integrity();
                requireAuthority(true);dataScope.requirePatientOrOwner(binding.patient,binding.owner);
                repository.requireChildrenAbsent(connection,binding.id);
                if(status==MedicalReviewSchema.Status.READY){
                    Proof proof=repository.proof(connection,binding,null);if(proof.pair.size()!=0)throw integrity();sameEvent(expectedWatermark,proof.watermark);
                    if(closure!=null)sameEvent(closure,repository.closure(connection,binding.id,closure.generation));
                }
            } else {
                BindingObservation now=repository.observe(connection,binding.id,true);now.requireIdentity(binding.id,true);expectedBinding.requireSame(now);requireAuthority(false);
                if(status==MedicalReviewSchema.Status.READY) {
                    Lifecycle lifecycle=sources.readInspectedLockedLifecycle(now.source(),connection,status);verifyResource();
                    Proof proof=repository.proof(connection,now,lifecycle);if(!expectedPair.equals(proof.pair))throw integrity();sameEvent(expectedWatermark,proof.watermark);
                    if(contentFingerprint!=null){if(!contentFingerprint.equals(sources.readLocked(now.source()).fingerprint()))throw integrity();repository.requireReset(connection,binding.id);}
                }
            }
            if(status==MedicalReviewSchema.Status.READY)repository.requireEvents(connection,events);
            sameCorrection(correction,readCorrection());
            Authority current=classify();if(!Objects.equals(actorKind,current.kind)||!Objects.equals(patientOwner,current.patientOwner))throw MedicalRecordCorrectionAuthorization.denied();
            requireAuthority(kind==Kind.DELETE);verifyResource();
        }
        private void requireAuthority(boolean deleted) {
            verifyActor();
            try{if(deleted)access.requireLockedDeletedSource(binding.source(),actor);else access.requireLockedCurrent(binding.source(),actor,true,kind==Kind.WORKSPACE_REVIEW);}
            catch(IllegalStateException denied){if("Access denied to source record.".equals(denied.getMessage()))throw MedicalRecordCorrectionAuthorization.denied();throw denied;}
            verifyActor();
        }
        private void verifyActor(){actor(actor);}
        private Authority classify()throws SQLException {
            verifyActor();
            String sql="SELECT u.id AS actor_id, EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.role_code='admin' AND r.status=1 AND COALESCE(r.deleted,0)=0) AS is_admin, EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.role_code='doctor' AND r.status=1 AND COALESCE(r.deleted,0)=0) AS is_doctor, (SELECT p.user_id FROM patient p WHERE p.id=? AND p.status=1 AND COALESCE(p.deleted,0)=0) AS patient_owner, EXISTS(SELECT 1 FROM doctor_patient_assignment d WHERE d.doctor_user_id=u.id AND d.patient_id=? AND d.status='ACTIVE') AS assigned FROM sys_user u WHERE u.id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 LIMIT 2";
            try(PreparedStatement s=connection.prepareStatement(sql)){
                s.setQueryTimeout(5);s.setObject(1,binding.patient);s.setObject(2,binding.patient);s.setObject(3,actor);
                try(ResultSet r=s.executeQuery()){
                    if(!r.next())throw MedicalRecordCorrectionAuthorization.denied();
                    if(!Long.valueOf(actor).equals(exact(r.getObject("actor_id"))))throw integrity();
                    boolean admin=flag(r,"is_admin"),doctor=flag(r,"is_doctor"),assigned=flag(r,"assigned");Long owner=exact(r.getObject("patient_owner"));MedicalReviewContentFingerprint.nullableId(owner);
                    if(r.next())throw integrity();verifyActor();
                    return new Authority(admin?"ADMIN":binding.patient!=null&&doctor&&assigned?"ASSIGNED_DOCTOR":Objects.equals(Long.valueOf(actor),binding.patient==null?binding.owner:owner)?"OWNER":"DELEGATE",owner);
                }
            }
        }
        public RuntimeException failure(Throwable failure){
            if(mutationAttempted)holder.setRollbackOnly();
            if(failure instanceof Error)throw (Error)failure;
            if(failure instanceof SQLException||failure instanceof DataAccessException)return MedicalReviewSchema.notReady();
            if(failure instanceof RuntimeException)return (RuntimeException)failure;
            return new IllegalStateException("The native medical-record operation could not finish.",failure);
        }
        @Override public void close(){if(mutationAttempted&&!finished)holder.setRollbackOnly();edit=null;closed=true;}
    }
    private static boolean flag(ResultSet r,String field)throws SQLException{Object raw=r.getObject(field);Long value=raw instanceof Boolean?(Boolean.TRUE.equals(raw)?1L:0L):exact(raw);if(value==null||(value!=0&&value!=1))throw integrity();return value==1;}
    private static final class Authority {final String kind;final Long patientOwner;Authority(String kind,Long patientOwner){this.kind=kind;this.patientOwner=patientOwner;}}
    private static void sameCorrection(MedicalRecordCorrectionRepository.State a,MedicalRecordCorrectionRepository.State b){
        if(a==null||b==null){if(a!=b)throw MedicalRecordCorrectionContracts.integrity();return;}
        if(!Objects.equals(a.patientId,b.patientId)||!Objects.equals(a.ownerId,b.ownerId)||a.baselineVersion!=b.baselineVersion||a.version!=b.version||a.sequence!=b.sequence||!a.validity.equals(b.validity)||!a.digest.equals(b.digest))throw MedicalRecordCorrectionContracts.integrity();
    }
    /** Defensive, normalized request intent. Actual fingerprints are accepted only after exact target equality. */
    private static final class EditIntent {
        final long oldItems,removedAttachments;
        final Map<String,Object> header;
        final List<Map<String,Object>> items=new ArrayList<>(),attachments=new ArrayList<>();
        final List<Map<String,Object>> newAttachments=new ArrayList<>();
        final Set<Long> itemIds=new HashSet<>(),attachmentIds=new HashSet<>();
        final Set<Integer> insertedItems=new HashSet<>(),insertedAttachments=new HashSet<>();
        EditIntent(MedicalReviewContentFingerprint.Content old,MedicalRecord record,List<MedicalRecordItem> requestedItems,List<MedicalRecordAttachment> requestedAttachments){
            Map<String,Object> envelope=WorkflowData.object(old.canonicalEnvelope());
            header=map(envelope.get("record"));List<Map<String,Object>> oldItemRows=rows(envelope.get("items")),oldAttachmentRows=rows(envelope.get("attachments"));
            oldItems=oldItemRows.size();Set<Long> allowedItems=new HashSet<>();Map<Long,Map<String,Object>> allowedAttachments=new HashMap<>();
            for(Map<String,Object> row:oldItemRows)allowedItems.add(number(row,"id"));
            for(Map<String,Object> row:oldAttachmentRows)allowedAttachments.put(number(row,"id"),row);
            if(!Objects.equals(record.getId(),old.recordId())||!Objects.equals(record.getPatientId(),old.patientId())||!Objects.equals(record.getUserId(),old.userId()))throw MedicalRecordCorrectionAuthorization.denied();
            if(!Objects.equals(header.get("createdAt"),time(record.getCreatedAt())))throw editScope();
            header.put("patientName",record.getPatientName());header.put("recordDate",record.getRecordDate()==null?null:record.getRecordDate().toString());
            header.put("recordType",record.getRecordType());header.put("hospitalName",record.getHospitalName());header.put("deptName",record.getDeptName());header.put("doctorName",record.getDoctorName());header.put("remark",record.getRemark());
            header.put("sourceType",record.getSourceType());header.put("sourceExternalId",record.getSourceExternalId());header.put("confidenceScore",decimal(record.getConfidenceScore()));header.put("aiRawReference",reference(record.getAiRawResult()));header.put("contentVersion",old.contentVersion()+1);
            if(requestedItems!=null)for(MedicalRecordItem item:requestedItems){
                if(item==null)throw MedicalRecordCorrectionContracts.invalid();Long id=item.getId();
                if(id!=null){MedicalRecordCorrectionContracts.id(id);if(!allowedItems.contains(id)||!itemIds.add(id))throw MedicalRecordCorrectionContracts.invalid();}
                items.add(WorkflowData.map("id",id,"recordId",old.recordId(),"itemName",item.getItemName(),"itemCode",item.getItemCode(),"resultValue",item.getResultValue(),"unit",item.getUnit(),"referenceRange",item.getReferenceRange(),"isAbnormal",item.getIsAbnormal()==null?0L:Long.valueOf(item.getIsAbnormal()),"aiConfidence",decimal(item.getAiConfidence()),"createdAt",null));
            }
            if(requestedAttachments!=null)for(MedicalRecordAttachment attachment:requestedAttachments){
                if(attachment==null)throw MedicalRecordCorrectionContracts.invalid();Long id=attachment.getId();
                if(id!=null){MedicalRecordCorrectionContracts.id(id);if(!allowedAttachments.containsKey(id)||!attachmentIds.add(id))throw MedicalRecordCorrectionContracts.invalid();attachments.add(new LinkedHashMap<>(allowedAttachments.get(id)));}
                else if(attachment.getFileContent()!=null&&!attachment.getFileContent().isEmpty()) {
                    Map<String,Object> row=WorkflowData.map("id",null,"recordId",old.recordId(),"filePath",attachment.getFilePath(),"fileName",attachment.getFileName(),"fileType",attachment.getFileType(),"fileSize",attachment.getFileSize(),"createdAt",null,"fileContentReference",reference(attachment.getFileContent()));
                    attachments.add(row);newAttachments.add(row);
                }
            }
            removedAttachments=oldAttachmentRows.size()-attachmentIds.size();
            if(items.size()>100||attachments.size()>100)throw MedicalReviewContentFingerprint.limit();
            // Worst-width safe identities and wall clocks budget the complete intended envelope before any write.
            List<Map<String,Object>> budgetItems=budget(items),budgetAttachments=budget(attachments);
            assemble(header,budgetItems,budgetAttachments);
        }
        void inserted(boolean attachment,int index,Long id,int count){
            List<Map<String,Object>> values=attachment?newAttachments:items;Set<Integer> inserted=attachment?insertedAttachments:insertedItems;Set<Long> ids=attachment?attachmentIds:itemIds;
            if(index<0||index>=values.size()||count!=1||!inserted.add(index))throw integrity();
            MedicalReviewContentFingerprint.id(id);Map<String,Object> row=values.get(index);Long expected=number(row,"id");
            if(expected!=null){if(!expected.equals(id))throw integrity();}
            else {if(!ids.add(id))throw integrity();row.put("id",id);}
        }
        void requireResult(MedicalReviewContentFingerprint.Content actual){
            if(insertedItems.size()!=items.size()||insertedAttachments.size()!=newAttachments.size())throw integrity();
            Map<String,Object> envelope=WorkflowData.object(actual.canonicalEnvelope());
            if(!header.equals(map(envelope.get("record"))))throw integrity();
            requireRows(items,rows(envelope.get("items")),new HashSet<>(items));
            requireRows(attachments,rows(envelope.get("attachments")),new HashSet<>(newAttachments));
        }
        private static void requireRows(List<Map<String,Object>> expected,List<Map<String,Object>> actual,Set<Map<String,Object>> generated){
            if(expected.size()!=actual.size())throw integrity();Map<Long,Map<String,Object>> actualById=new HashMap<>();for(Map<String,Object> row:actual)if(actualById.put(number(row,"id"),row)!=null)throw integrity();
            for(Map<String,Object> row:expected){Map<String,Object> found=actualById.get(number(row,"id"));if(found==null)throw integrity();
                Map<String,Object> wanted=new LinkedHashMap<>(row);
                if(generated.contains(row)){
                    Object stamp=found.get("createdAt");if(!(stamp instanceof String))throw integrity();MedicalReviewContentFingerprint.time((String)stamp);wanted.put("createdAt",stamp);
                }
                if(!wanted.equals(found))throw integrity();
            }
        }
        private static List<Map<String,Object>> budget(List<Map<String,Object>> rows){
            List<Map<String,Object>> values=new ArrayList<>();long id=MAX_ID-100;
            for(Map<String,Object> row:rows){Map<String,Object> value=new LinkedHashMap<>(row);value.put("id",++id);if(value.get("createdAt")==null)value.put("createdAt","9999-12-31T23:59:59.999999");values.add(value);}return values;
        }
    }
    private static WorkflowException editScope(){return conflict("EDIT_SCOPE","This edit changes fields that cannot be changed in the review workflow.");}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return new LinkedHashMap<>((Map<String,Object>)value);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static Long number(Map<String,Object> value,String key){return exact(value.get(key));}
    private static String string(Map<String,Object> value,String key){return (String)value.get(key);}
    private static String time(LocalDateTime value){return value==null?null:value.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS",Locale.ROOT));}
    private static String decimal(BigDecimal value){return value==null?null:value.toPlainString();}
    private static Map<String,Object> reference(String text){
        if(text==null)return null;
        try{int bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text)).remaining();return WorkflowData.map("byteLength",(long)bytes,"sha256",WorkflowData.hash(text));}
        catch(java.nio.charset.CharacterCodingException failure){throw MedicalRecordCorrectionContracts.invalid();}
    }
    private static MedicalReviewContentFingerprint.TextReference ref(Object value){if(value==null)return null;Map<String,Object> reference=map(value);return new MedicalReviewContentFingerprint.TextReference(number(reference,"byteLength"),string(reference,"sha256"));}
    private static void assemble(Map<String,Object> h,List<Map<String,Object>> items,List<Map<String,Object>> attachments){
        MedicalReviewContentFingerprint.NativeHeader header=new MedicalReviewContentFingerprint.NativeHeader(number(h,"id"),number(h,"patientId"),number(h,"userId"),string(h,"patientName"),string(h,"recordDate"),string(h,"recordType"),string(h,"hospitalName"),string(h,"deptName"),string(h,"doctorName"),string(h,"remark"),string(h,"sourceType"),string(h,"sourceExternalId"),string(h,"confidenceScore"),string(h,"createdAt"),number(h,"contentVersion"),ref(h.get("aiRawReference")),ref(h.get("importFactsReference")),"REVIEW_REQUIRED",null,null,null);
        List<MedicalReviewContentFingerprint.NativeItem> nativeItems=new ArrayList<>();
        for(Map<String,Object> i:items)nativeItems.add(new MedicalReviewContentFingerprint.NativeItem(number(i,"id"),number(i,"recordId"),string(i,"itemName"),string(i,"itemCode"),string(i,"resultValue"),string(i,"unit"),string(i,"referenceRange"),number(i,"isAbnormal"),string(i,"aiConfidence"),string(i,"createdAt")));
        List<MedicalReviewContentFingerprint.NativeAttachment> nativeAttachments=new ArrayList<>();
        for(Map<String,Object> a:attachments)nativeAttachments.add(new MedicalReviewContentFingerprint.NativeAttachment(number(a,"id"),number(a,"recordId"),string(a,"filePath"),string(a,"fileName"),string(a,"fileType"),number(a,"fileSize"),string(a,"createdAt"),ref(a.get("fileContentReference"))));
        MedicalReviewContentFingerprint.assemble(header,nativeItems,nativeAttachments);
    }
}
