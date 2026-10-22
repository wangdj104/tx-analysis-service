package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.dto.MedicalRecordReviewSummary;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader;
import org.familyhealthcare.service.medicalreview.MedicalReviewSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.util.*;

/** 仅投影来源状态，不授予任何读取或写入权限。结构结果只在本次请求或事务内复用。 */
@Component
public class MedicalRecordValidityProjection {
    private final JdbcTemplate jdbc;
    private final MedicalRecordCorrectionSchema schema;
    private final boolean enabled;
    private final MedicalReviewQualificationReader qualification;
    private final Object transactionKey=new Object();
    private final String requestKey=getClass().getName()+".storage";
    public MedicalRecordValidityProjection(JdbcTemplate jdbc,MedicalRecordCorrectionSchema schema,
            @Value("${medical-record-correction.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.schema=schema;this.enabled=enabled;this.qualification=null;
    }
    @Autowired
    public MedicalRecordValidityProjection(JdbcTemplate jdbc,MedicalRecordCorrectionSchema schema,
            @Value("${medical-record-correction.enabled:false}") boolean enabled,MedicalReviewQualificationReader qualification) {
        this.jdbc=jdbc;this.schema=schema;this.enabled=enabled;this.qualification=qualification;
        if(qualification==null)throw MedicalReviewSchema.notReady();qualification.requireDataSource(jdbc.getDataSource());
    }
    /** Identity only; callers must run this before opening a transaction or inspecting storage. */
    public void requireDataSource(javax.sql.DataSource expected) {
        if(expected==null||jdbc.getDataSource()!=expected||qualification==null)throw MedicalReviewSchema.notReady();
        qualification.requireDataSource(expected);
    }
    public Scope open() {
        MedicalRecordCorrectionSchema.Status status=null;
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            status=(MedicalRecordCorrectionSchema.Status)TransactionSynchronizationManager.getResource(transactionKey);
            if(status==null) {
                status=inspect();TransactionSynchronizationManager.bindResource(transactionKey,status);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void suspend(){TransactionSynchronizationManager.unbindResourceIfPossible(transactionKey);}
                    @Override public void resume(){TransactionSynchronizationManager.bindResource(transactionKey,stored);}
                    private final MedicalRecordCorrectionSchema.Status stored=(MedicalRecordCorrectionSchema.Status)TransactionSynchronizationManager.getResource(transactionKey);
                    @Override public void afterCompletion(int completion){TransactionSynchronizationManager.unbindResourceIfPossible(transactionKey);}
                });
            }
        } else {
            RequestAttributes request=RequestContextHolder.getRequestAttributes();
            if(request!=null)status=(MedicalRecordCorrectionSchema.Status)request.getAttribute(requestKey,RequestAttributes.SCOPE_REQUEST);
            if(status==null){status=inspect();if(request!=null)request.setAttribute(requestKey,status,RequestAttributes.SCOPE_REQUEST);}
        }
        return new Scope(status);
    }
    private MedicalRecordCorrectionSchema.Status inspect() {
        MedicalRecordCorrectionSchema.Status status=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<MedicalRecordCorrectionSchema.Status>)schema::inspectStorage);
        if(status==MedicalRecordCorrectionSchema.Status.INCOMPATIBLE||enabled&&status!=MedicalRecordCorrectionSchema.Status.READY)throw MedicalRecordCorrectionSchema.notReady();
        return status;
    }
    public final class Scope {
        public final MedicalRecordCorrectionSchema.Status storage;
        private Scope(MedicalRecordCorrectionSchema.Status status){storage=status;}
        public boolean ready(){return storage==MedicalRecordCorrectionSchema.Status.READY;}
        /** 所有调用者只能传服务器常量别名；这是查询条件，不是权限条件。 */
        public String activeSql(String alias) {
            alias(alias);return ready()?"NOT EXISTS (SELECT 1 FROM medical_record_correction_state mv WHERE mv.record_id="+alias+"id AND mv.validity='INVALIDATED')":"1=1";
        }
        public String untrackedSql(String alias){alias(alias);return ready()?"NOT EXISTS (SELECT 1 FROM medical_record_correction_state mv WHERE mv.record_id="+alias+"id)":"1=1";}
        private void alias(String alias){if(!Arrays.asList("","r.","m.","n.","medical_record.").contains(alias))throw new IllegalArgumentException("Invalid source alias.");}
        public Map<Long,State> batch(Collection<Long> input) {
            LinkedHashSet<Long> ids=new LinkedHashSet<>(input);
            if(input.size()>100||ids.contains(null))throw MedicalRecordCorrectionContracts.limit();
            if(ids.isEmpty())return Collections.emptyMap();
            for(long id:ids)MedicalRecordCorrectionContracts.id(id);
            String columns="r.id,r.patient_id,r.user_id,r.content_version,r.verification_status";
            String joins="";
            if(ready()) {
                columns+=",s.record_id AS managed_id,s.patient_id AS state_patient,s.owner_user_id,s.current_content_version,s.validity,h.after_version,h.validity AS head_validity,CASE WHEN EXISTS(SELECT 1 FROM medical_record_correction_history p WHERE p.record_id=r.id AND p.history_sequence>s.current_history_sequence) THEN 1 ELSE 0 END AS pending";
                joins=" LEFT JOIN medical_record_correction_state s ON s.record_id=r.id LEFT JOIN medical_record_correction_history h ON h.record_id=s.record_id AND h.history_sequence=s.current_history_sequence";
            }
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            List<State> rows=jdbc.query("SELECT "+columns+" FROM medical_record r"+joins+" WHERE r.id IN ("+placeholders+") ORDER BY r.id",(r,n)->{
                long id=r.getLong("id");Long patient=WorkflowData.nullableId(r,"patient_id"),owner=WorkflowData.nullableId(r,"user_id"),version=WorkflowData.nullableId(r,"content_version");
                boolean managed=ready()&&r.getObject("managed_id")!=null;
                String validity=managed?r.getString("validity"):"UNTRACKED";
                if(managed&&(!Objects.equals(patient,WorkflowData.nullableId(r,"state_patient"))||!Objects.equals(owner,WorkflowData.nullableId(r,"owner_user_id"))||!Objects.equals(version,WorkflowData.nullableId(r,"current_content_version"))||!Objects.equals(version,WorkflowData.nullableId(r,"after_version"))||!Objects.equals(validity,r.getString("head_validity"))||r.getInt("pending")!=0||!("ACTIVE".equals(validity)||"INVALIDATED".equals(validity))))throw MedicalRecordCorrectionContracts.integrity();
                if(managed&&(version==null||version<1||version>MedicalRecordCorrectionContracts.MAX_ID))throw MedicalRecordCorrectionContracts.integrity();
                return new State(id,patient,owner,version,managed,validity,r.getString("verification_status"));
            },ids.toArray());
            Map<Long,State> result=new LinkedHashMap<>();for(State row:rows)result.put(row.id,row);return result;
        }
        /** 新组合只读消费者使用同一批数据的不可变签名；不缓存来源内容，也不授访问权。 */
        public Map<Long,State> batchSnapshot(Collection<Long> input) {
            LinkedHashSet<Long> ids=new LinkedHashSet<>(input);
            if(input.size()>100||ids.contains(null))throw MedicalRecordCorrectionContracts.limit();
            if(ids.isEmpty())return Collections.emptyMap();
            for(long id:ids)MedicalRecordCorrectionContracts.id(id);
            String columns="r.id,r.patient_id,r.user_id,r.content_version,r.verification_status";
            String joins="";
            if(ready()) {
                columns+=",s.record_id AS managed_id,s.patient_id AS state_patient,s.owner_user_id,s.current_content_version,s.validity,h.after_version,h.validity AS head_validity,s.current_history_sequence,s.content_digest AS state_digest,h.id AS head_id,h.history_sequence AS head_sequence,h.content_digest AS head_digest,CASE WHEN EXISTS(SELECT 1 FROM medical_record_correction_history p WHERE p.record_id=r.id AND p.history_sequence>s.current_history_sequence) THEN 1 ELSE 0 END AS pending";
                joins=" LEFT JOIN medical_record_correction_state s ON s.record_id=r.id LEFT JOIN medical_record_correction_history h ON h.record_id=s.record_id AND h.history_sequence=s.current_history_sequence";
            }
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            List<State> rows=jdbc.query("SELECT "+columns+" FROM medical_record r"+joins+" WHERE r.id IN ("+placeholders+") ORDER BY r.id",statement->{statement.setQueryTimeout(5);int index=1;for(long source:ids)statement.setLong(index++,source);},(r,n)->{
                long id=r.getLong("id");Long patient=WorkflowData.nullableId(r,"patient_id"),owner=WorkflowData.nullableId(r,"user_id"),version=WorkflowData.nullableId(r,"content_version");
                boolean managed=ready()&&r.getObject("managed_id")!=null;
                String validity=managed?r.getString("validity"):"UNTRACKED";
                if(managed&&(!Objects.equals(patient,WorkflowData.nullableId(r,"state_patient"))||!Objects.equals(owner,WorkflowData.nullableId(r,"owner_user_id"))||!Objects.equals(version,WorkflowData.nullableId(r,"current_content_version"))||!Objects.equals(version,WorkflowData.nullableId(r,"after_version"))||!Objects.equals(validity,r.getString("head_validity"))||r.getInt("pending")!=0||!("ACTIVE".equals(validity)||"INVALIDATED".equals(validity))))throw MedicalRecordCorrectionContracts.integrity();
                if(managed&&(version==null||version<1||version>MedicalRecordCorrectionContracts.MAX_ID))throw MedicalRecordCorrectionContracts.integrity();
                String signature=WorkflowData.json(WorkflowData.map("id",id,"patient",patient,"owner",owner,"version",version,"review",r.getString("verification_status"),
                        "managed",ready()?r.getObject("managed_id"):null,"statePatient",ready()?r.getObject("state_patient"):null,"stateOwner",ready()?r.getObject("owner_user_id"):null,
                        "stateVersion",ready()?r.getObject("current_content_version"):null,"validity",ready()?r.getString("validity"):null,
                        "sequence",ready()?r.getObject("current_history_sequence"):null,"stateDigest",ready()?r.getString("state_digest"):null,
                        "head",ready()?r.getObject("head_id"):null,"headSequence",ready()?r.getObject("head_sequence"):null,"headVersion",ready()?r.getObject("after_version"):null,
                        "headValidity",ready()?r.getString("head_validity"):null,"headDigest",ready()?r.getString("head_digest"):null,"pending",ready()?r.getObject("pending"):null));
                return new State(id,patient,owner,version,managed,validity,r.getString("verification_status"),signature);
            });
            Map<Long,State> result=new LinkedHashMap<>();for(State row:rows)result.put(row.id,row);return result;
        }
        /** Explicit opt-in. No legacy caller is retargeted by this adapter. */
        public Map<Long,State> qualifiedBatch(Collection<Long> input) {return qualified(input);}
        /** The actual connection decides snapshot/current semantics, never this method's name. */
        public Map<Long,State> qualifiedSnapshot(Collection<Long> input) {return qualified(input);}
        /** Snapshot-only opt-in. In particular, an unowned call must never start the reader's RC owner. */
        public SnapshotQualification qualifiedSnapshotResult(Collection<Long> input) {
            Object[] owner=snapshotResource(null);
            try {
                MedicalReviewQualificationReader.Batch observed=reader().read(input);
                snapshotResource(owner);
                if(observed.semantics!=MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT)throw MedicalReviewSchema.notReady();
                return new SnapshotQualification(observed);
            } catch(RuntimeException failure) {
                snapshotResource(owner);
                // Preserve the actual MetadataUnavailable type only while this owner is still healthy.
                throw failure;
            }
        }
        private Object[] snapshotResource(Object[] expected) {
            reader();
            if(!TransactionSynchronizationManager.isActualTransactionActive()
                    ||!TransactionSynchronizationManager.isSynchronizationActive()
                    ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw MedicalReviewSchema.notReady();
            Object value=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if(value==null||value.getClass()!=ConnectionHolder.class)throw MedicalReviewSchema.notReady();
            ConnectionHolder holder=(ConnectionHolder)value;
            if(!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()==null
                    ||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw MedicalReviewSchema.notReady();
            SimpleConnectionHandle handle=(SimpleConnectionHandle)holder.getConnectionHandle();Connection connection=handle.getConnection();
            if(expected!=null&&(expected[0]!=Thread.currentThread()||expected[1]!=holder||expected[2]!=handle||expected[3]!=connection))throw MedicalReviewSchema.notReady();
            try {
                if(connection==null||holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()
                        ||connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)throw MedicalReviewSchema.notReady();
            } catch(SQLException|IllegalStateException failure){throw MedicalReviewSchema.notReady();}
            return new Object[]{Thread.currentThread(),holder,handle,connection};
        }
        private Map<Long,State> qualified(Collection<Long> input) {
            try {MedicalReviewQualificationReader.Batch observed=reader().read(input);Map<Long,State> result=new LinkedHashMap<>();
                for(MedicalReviewQualificationReader.Observation row:observed.observations.values())result.put(row.id,new State(row,observed));
                return Collections.unmodifiableMap(result);
            } catch(MedicalReviewQualificationReader.MetadataUnavailable unavailable){throw unavailable.failure();}
        }
        /** Identity only: no connection acquisition or qualification. */
        void requireDataSource(javax.sql.DataSource expected){reader().requireDataSource(expected);}
        private MedicalReviewQualificationReader reader(){if(qualification==null)throw MedicalReviewSchema.notReady();qualification.requireDataSource(jdbc.getDataSource());return qualification;}
        /** Display-only: native/parent9 failures propagate; only classified new metadata can be neutral. */
        public Map<Long,HumanLinkObservation> humanLinkBatch(Collection<Long> input) {
            Object[] admitted=humanResource(null);Map<Long,State> nativeStates=batchSnapshot(input);humanResource(admitted);MedicalReviewQualificationReader.Batch observed;
            try{observed=reader().read(input);}catch(MedicalReviewQualificationReader.MetadataUnavailable unavailable){
                Map<Long,HumanLinkObservation> result=new LinkedHashMap<>();for(Map.Entry<Long,State> row:nativeStates.entrySet())result.put(row.getKey(),new HumanLinkObservation(row.getValue(),null,null));return Collections.unmodifiableMap(result);
            }
            Map<Long,HumanLinkObservation> result=new LinkedHashMap<>();
            for(Map.Entry<Long,State> entry:nativeStates.entrySet()){
                State old=entry.getValue();MedicalReviewQualificationReader.Observation row=observed.observations.get(entry.getKey());
                if(row==null||!row.exists||!Objects.equals(old.patientId,row.patientId)||!Objects.equals(old.ownerId,row.ownerId)||!Objects.equals(old.version,row.version)
                        ||old.managed!=row.managed||!Objects.equals(old.validity,row.validity)||!Objects.equals(old.review,row.legacyVerification))throw MedicalRecordCorrectionContracts.integrity();
                result.put(entry.getKey(),new HumanLinkObservation(old,observed.reviewStorage==MedicalReviewSchema.Status.ABSENT?null:row,observed.reviewStorage==MedicalReviewSchema.Status.ABSENT?null:observed));
            }
            return Collections.unmodifiableMap(result);
        }
        private Object[] humanResource(Object[] expected) {
            reader();
            boolean actual=TransactionSynchronizationManager.isActualTransactionActive();Object value=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if(!actual){if(value!=null||TransactionSynchronizationManager.isSynchronizationActive()||expected!=null&&expected.length!=0)throw MedicalReviewSchema.notReady();return new Object[0];}
            if(!TransactionSynchronizationManager.isSynchronizationActive()||value==null||value.getClass()!=ConnectionHolder.class)throw MedicalReviewSchema.notReady();
            ConnectionHolder holder=(ConnectionHolder)value;
            if(!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw MedicalReviewSchema.notReady();
            SimpleConnectionHandle handle=(SimpleConnectionHandle)holder.getConnectionHandle();Connection connection=handle.getConnection();
            if(expected!=null&&(expected.length!=3||expected[0]!=holder||expected[1]!=handle||expected[2]!=connection))throw MedicalReviewSchema.notReady();
            try{if(connection==null||holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()
                    ||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED&&connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)throw MedicalReviewSchema.notReady();}
            catch(SQLException|IllegalStateException failure){throw MedicalReviewSchema.notReady();}
            return new Object[]{holder,handle,connection};
        }
        public State one(long id){return batch(Collections.singleton(id)).get(id);}
        public void projectRows(List<Map<String,Object>> rows,String idKey) {
            for(int offset=0;offset<rows.size();offset+=100){List<Map<String,Object>> part=rows.subList(offset,Math.min(offset+100,rows.size()));List<Long> ids=new ArrayList<>();for(Map<String,Object> row:part)ids.add(((Number)row.get(idKey)).longValue());Map<Long,State> states=batch(ids);
                for(Map<String,Object> row:part){State state=states.get(((Number)row.get(idKey)).longValue());if(state==null){row.put("sourceStatus","SOURCE_UNAVAILABLE");continue;}boolean captured=row.containsKey("contentVersion");boolean changed=captured&&(!Objects.equals(row.get("contentVersion"),state.version)||!Objects.equals(row.get("verificationStatus"),state.review));row.put("managed",state.managed);row.put("validity",state.validity);if(!captured){row.put("verificationStatus",state.review);row.put("contentVersion",state.version);}row.put("sourceStatus",state.managed&&changed?"SOURCE_CHANGED":state.status());}
            }
        }
        public void project(List<MedicalRecord> records) {
            for(int offset=0;offset<records.size();offset+=100){List<MedicalRecord> part=records.subList(offset,Math.min(offset+100,records.size()));List<Long> ids=new ArrayList<>();for(MedicalRecord record:part)ids.add(record.getId());Map<Long,State> states=batch(ids);
                for(MedicalRecord record:part){State state=states.get(record.getId());if(state==null)throw MedicalRecordCorrectionContracts.integrity();record.setManaged(state.managed);record.setValidity(state.validity);record.setSourceStatus(state.managed&&(!Objects.equals(record.getContentVersion(),state.version)||!Objects.equals(record.getVerificationStatus(),state.review))?"SOURCE_CHANGED":state.status());}
            }
        }
    }
    /** A genuine reader batch, including the empty snapshot's readiness and observation time. */
    public static final class SnapshotQualification {
        public final Map<Long,State> states;
        public final MedicalReviewQualificationReader.Semantics semantics;
        public final java.time.Instant checkedAt;
        public final MedicalReviewSchema.Status reviewStorage;
        public final MedicalRecordCorrectionSchema.Status parent9Storage;
        private SnapshotQualification(MedicalReviewQualificationReader.Batch observed) {
            Map<Long,State> converted=new LinkedHashMap<>();
            for(MedicalReviewQualificationReader.Observation row:observed.observations.values())converted.put(row.id,new State(row,observed));
            states=Collections.unmodifiableMap(converted);semantics=observed.semantics;checkedAt=observed.checkedAt;
            reviewStorage=observed.reviewStorage;parent9Storage=observed.parent9Storage;
        }
    }
    /** Pure opt-in display copy. Callers separately own access and current-content release. */
    public static void projectReviewSummary(MedicalRecord record, State state) {
        if(record==null)throw new IllegalArgumentException("Medical record is required.");
        record.setReviewSummary(null);
        if(state==null||!state.reviewObserved)return;
        if(!Objects.equals(record.getId(),state.id)||state.version==null
                ||!Objects.equals(record.getPatientId(),state.patientId)||!Objects.equals(record.getUserId(),state.ownerId)
                ||!Objects.equals(record.getContentVersion(),state.version)||!Objects.equals(record.getVerificationStatus(),state.review))
            throw new WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","Current medical record review metadata is unavailable.");
        record.setReviewSummary(MedicalRecordReviewSummary.fromObservedState(state));
    }
    public static final class State {
        public final long id;public final Long patientId,ownerId,version;public final boolean managed;public final String validity,review,signature;
        public final boolean reviewObserved,reviewParticipating,automaticInputEligible;
        public final MedicalReviewQualificationReader.ChannelFacts source,doctor;
        public final MedicalReviewQualificationReader.Semantics qualificationSemantics;
        public final java.time.Instant checkedAt;
        public final String sourceReleaseSignature;
        private final String qualifiedStatus;
        final MedicalReviewSchema.Status reviewStorage;
        final MedicalRecordCorrectionSchema.Status parent9Storage;
        final String liveGeneration;
        private State(MedicalReviewQualificationReader.Observation row,MedicalReviewQualificationReader.Batch batch){
            id=row.id;patientId=row.patientId;ownerId=row.ownerId;version=row.version;managed=row.managed;validity=row.validity;review=row.legacyVerification;signature=row.signature();
            reviewObserved=true;reviewParticipating=row.reviewParticipating;automaticInputEligible=row.automaticInputEligible;source=row.source;doctor=row.doctor;
            qualificationSemantics=batch.semantics;checkedAt=batch.checkedAt;sourceReleaseSignature=row.sourceReleaseSignature();qualifiedStatus=row.sourceStatus;
            reviewStorage=batch.reviewStorage;parent9Storage=batch.parent9Storage;liveGeneration=row.liveGeneration;
        }
        private State(long id,Long patient,Long owner,Long version,boolean managed,String validity,String review){this(id,patient,owner,version,managed,validity,review,null);}
        private State(long id,Long patient,Long owner,Long version,boolean managed,String validity,String review,String signature){this.id=id;this.patientId=patient;this.ownerId=owner;this.version=version;this.managed=managed;this.validity=validity;this.review=review;this.signature=signature;reviewObserved=false;reviewParticipating=false;automaticInputEligible=!managed||"ACTIVE".equals(validity)&&"VERIFIED".equals(review);source=null;doctor=null;qualificationSemantics=null;checkedAt=null;sourceReleaseSignature=null;qualifiedStatus=null;reviewStorage=null;parent9Storage=null;liveGeneration=null;}
        /** Readiness of this admitted scalar observation, not a permission or a fresh read. */
        public boolean reviewSummaryMetadataAvailable() {
            if(!reviewObserved)return false;
            if((reviewStorage!=MedicalReviewSchema.Status.READY&&reviewStorage!=MedicalReviewSchema.Status.ABSENT)
                    ||(parent9Storage!=MedicalRecordCorrectionSchema.Status.READY&&parent9Storage!=MedicalRecordCorrectionSchema.Status.ABSENT))throw MedicalReviewSchema.notReady();
            if("SOURCE_UNAVAILABLE".equals(qualifiedStatus)) {
                if(patientId!=null||ownerId!=null||version!=null||validity!=null||review!=null||managed||source!=null||doctor!=null||reviewParticipating||automaticInputEligible)
                    throw new WorkflowException(409,"MEDICAL_REVIEW_INTEGRITY","Medical review metadata is inconsistent.");
                return false;
            }
            if(version==null||version<1||version>9007199254740991L||managed&&parent9Storage!=MedicalRecordCorrectionSchema.Status.READY
                    ||reviewStorage==MedicalReviewSchema.Status.ABSENT&&(source!=null||doctor!=null||reviewParticipating))
                throw new WorkflowException(409,"MEDICAL_REVIEW_INTEGRITY","Medical review metadata is inconsistent.");
            return reviewStorage==MedicalReviewSchema.Status.READY;
        }
        public boolean active(){return (!reviewObserved||!"SOURCE_UNAVAILABLE".equals(qualifiedStatus))&&(!managed||"ACTIVE".equals(validity));}
        public boolean eligible(){return reviewObserved?automaticInputEligible:!managed||active()&&"VERIFIED".equals(review);}
        public String status(){if(reviewObserved)return qualifiedStatus;return !managed?"UNTRACKED":!active()?"SOURCE_INVALIDATED":!eligible()?"SOURCE_REVIEW_REQUIRED":"CURRENT";}
    }
    /** Cannot be supplied as a strict State; exposes no reviewer identity or review text. */
    public static final class HumanLinkObservation {
        private final String observedFacts;
        public final State nativeState;
        public final boolean metadataAvailable;
        public final String sourceState,doctorState,sourceStatus;
        public final MedicalReviewQualificationReader.Semantics qualificationSemantics;
        public final java.time.Instant checkedAt;
        private HumanLinkObservation(State nativeState,MedicalReviewQualificationReader.Observation row,MedicalReviewQualificationReader.Batch batch){
            this.nativeState=nativeState;metadataAvailable=row!=null;sourceState=row==null||row.source==null?null:row.source.state;doctorState=row==null||row.doctor==null?null:row.doctor.state;
            sourceStatus=row==null?"SOURCE_UNAVAILABLE":row.sourceStatus;qualificationSemantics=batch==null?null:batch.semantics;checkedAt=batch==null?null:batch.checkedAt;
            observedFacts=WorkflowData.json(WorkflowData.map("native",nativeState.signature,"available",row!=null,
                    "review",row==null?null:row.signature(),"parent9Storage",batch==null?null:batch.parent9Storage.name(),
                    "reviewStorage",batch==null?null:batch.reviewStorage.name()));
        }
        /** Fact equality only; observation time and semantics remain separate caller checks. */
        public boolean sameObservedFacts(HumanLinkObservation other){return other!=null&&observedFacts.equals(other.observedFacts);}
    }
    public static BigDecimal exactNumeric(String value) {
        if(value==null||value.length()>500||!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))return null;
        try{BigDecimal number=new BigDecimal(value);return number;}catch(NumberFormatException invalid){return null;}
    }
    public static BigDecimal plotNumber(String value){BigDecimal number=exactNumeric(value);if(number==null)return null;double plotted=number.doubleValue();return Double.isFinite(plotted)&&(plotted!=0.0||number.signum()==0)?number:null;}
    public static boolean exactUnit(String actual,String expected){return actual!=null&&!actual.trim().isEmpty()&&expected!=null&&!expected.trim().isEmpty()&&actual.equals(expected);}
    public static Integer sourceFlag(Integer value){return value!=null&&(value==-1||value==0||value==1)?value:null;}
}
