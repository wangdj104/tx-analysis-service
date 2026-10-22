package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Dormant scalar observer. Observations neither grant access nor prove mutation locks. */
@Component
public final class MedicalReviewQualificationReader {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final DataSourceTransactionManager transactions;
    private final MedicalReviewSchema schema;
    private final MedicalRecordCorrectionSchema corrections;
    private final boolean correctionEnabled;

    public MedicalReviewQualificationReader(JdbcTemplate jdbc,DataSourceTransactionManager transactions,
            MedicalReviewSchema schema,MedicalRecordCorrectionSchema corrections,
            @Value("${medical-record-correction.enabled:false}") boolean correctionEnabled) {
        if(jdbc==null||jdbc.getDataSource()==null||transactions==null||schema==null||corrections==null)throw MedicalReviewSchema.notReady();
        this.jdbc=jdbc;this.dataSource=jdbc.getDataSource();this.transactions=transactions;
        this.schema=schema;this.corrections=corrections;this.correctionEnabled=correctionEnabled;graph();
    }
    /** Identity comparison only: this method never acquires a connection. */
    public void requireDataSource(DataSource expected){if(expected!=dataSource)throw MedicalReviewSchema.notReady();graph();}
    private void graph(){if(jdbc.getDataSource()!=dataSource||transactions.getDataSource()!=dataSource)throw MedicalReviewSchema.notReady();}

    public enum Semantics { CURRENT_CHECK, CALLER_TRANSACTION_SNAPSHOT }
    /** Classified new-review failure only. Native/parent9/resource failures never use this type. */
    public static final class MetadataUnavailable extends RuntimeException {
        private final int status; private final String code;
        private MetadataUnavailable(int status,String code){super("Medical review metadata is unavailable.");this.status=status;this.code=code;}
        public WorkflowException failure(){return new WorkflowException(status,code,getMessage());}
    }
    static MetadataUnavailable integrity(){return new MetadataUnavailable(409,"MEDICAL_REVIEW_INTEGRITY");}
    private static MetadataUnavailable unstable(){return new MetadataUnavailable(409,"CURRENT_RESULT_UNAVAILABLE");}

    public static final class ChannelFacts {
        public final String state,operation,actorKind,occurredAt;
        public final long contentVersion,decisionVersion,cycle,actorId;
        private final String generation;
        ChannelFacts(MedicalReviewQualificationRepository.ReviewState s,MedicalReviewQualificationRepository.Event e){
            state=s.state.name();contentVersion=s.content;decisionVersion=s.decision;cycle=s.cycle;generation=s.generation;
            operation=e.operation.name();actorId=e.actor;actorKind=e.kind;occurredAt=e.time;
        }
    }
    public static final class Observation {
        public final long id; public final Long patientId,ownerId,version;
        public final boolean exists,managed,hasReviewFootprint,reviewParticipating,automaticInputEligible;
        public final String validity,legacyVerification,sourceStatus;
        public final String liveGeneration;
        public final ChannelFacts source,doctor;
        private final String signature,sourceReleaseSignature;
        Observation(long id,Long patient,Long owner,Long version,boolean exists,boolean managed,String validity,String legacy,
                boolean footprint,ChannelFacts source,ChannelFacts doctor,String signature,String release){
            this.id=id;patientId=patient;ownerId=owner;this.version=version;this.exists=exists;this.managed=managed;
            this.validity=validity;legacyVerification=legacy;hasReviewFootprint=footprint;this.source=source;this.doctor=doctor;
            reviewParticipating=source!=null||doctor!=null;
            liveGeneration=source!=null?source.generation:doctor!=null?doctor.generation:null;
            boolean active=exists&&(!managed||"ACTIVE".equals(validity));
            automaticInputEligible=active&&(reviewParticipating?source!=null&&"APPROVED".equals(source.state):!managed||"VERIFIED".equals(legacy));
            sourceStatus=!exists?"SOURCE_UNAVAILABLE":!active?"SOURCE_INVALIDATED":!reviewParticipating?(!managed?"UNTRACKED":automaticInputEligible?"CURRENT":"SOURCE_REVIEW_REQUIRED"):
                    automaticInputEligible?"CURRENT":source!=null&&"SUPERSEDED".equals(source.state)?"SOURCE_CHANGED":"SOURCE_REVIEW_REQUIRED";
            this.signature=signature;sourceReleaseSignature=release;
        }
        public String signature(){return signature;}
        public String sourceReleaseSignature(){return sourceReleaseSignature;}
    }
    public static final class Batch {
        public final MedicalReviewSchema.Status reviewStorage;
        public final MedicalRecordCorrectionSchema.Status parent9Storage;
        public final Semantics semantics; public final Instant checkedAt;
        public final Map<Long,Observation> observations;
        private final String signature;
        Batch(MedicalReviewSchema.Status review,MedicalRecordCorrectionSchema.Status parent9,Semantics semantics,
                Map<Long,Observation> observations,String signature){reviewStorage=review;parent9Storage=parent9;this.semantics=semantics;
            checkedAt=Instant.now();this.observations=Collections.unmodifiableMap(new LinkedHashMap<>(observations));this.signature=signature;}
    }

    /** At most 100 distinct, already-authorized safe identities. Missing native identities are explicit. */
    public Batch read(Collection<Long> authorizedSourceIds) {
        LinkedHashSet<Long> ids=new LinkedHashSet<>();
        if(authorizedSourceIds==null)throw limit();
        for(Long id:authorizedSourceIds){MedicalReviewContracts.id(id);ids.add(id);if(ids.size()>100)throw limit();}
        graph();
        if(TransactionSynchronizationManager.isActualTransactionActive())return observe(ids);
        // Do not let REQUIRED attach to an unknown/synchronization-only or pre-bound owner.
        if(TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.hasResource(dataSource))throw MedicalReviewSchema.notReady();
        TransactionTemplate template=new TransactionTemplate(transactions);
        template.setReadOnly(true);template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template.execute(status->observe(ids));
    }
    private static WorkflowException limit(){return new WorkflowException(413,"MEDICAL_REVIEW_LIMIT","At most 100 distinct source identities are supported per batch.");}
    private Batch observe(LinkedHashSet<Long> ids){
        try {
            Resource resource=new Resource();
            if(resource.isolation==Connection.TRANSACTION_REPEATABLE_READ){
                MedicalReviewQualificationRepository.Frame frame=collect(resource,ids);
                resource.verify();if(frame.invalid)throw integrity();return frame.batch(Semantics.CALLER_TRANSACTION_SNAPSHOT);
            }
            MedicalReviewQualificationRepository.Frame previous=null;
            for(int i=0;i<3;i++){
                MedicalReviewQualificationRepository.Frame current=collect(resource,ids);resource.verify();
                if(previous!=null&&previous.signature.equals(current.signature)){
                    if(current.invalid||previous.invalid)throw integrity();return current.batch(Semantics.CURRENT_CHECK);
                }
                previous=current;
            }
            throw unstable();
        }catch(SQLException failure){throw MedicalReviewSchema.notReady();}
    }
    private MedicalReviewQualificationRepository.Frame collect(Resource resource,LinkedHashSet<Long> ids)throws SQLException{
        resource.verify();MedicalRecordCorrectionSchema.Status parent9=corrections.inspectStorage(resource.connection);resource.verify();
        if(parent9==MedicalRecordCorrectionSchema.Status.INCOMPATIBLE||correctionEnabled&&parent9!=MedicalRecordCorrectionSchema.Status.READY)throw MedicalRecordCorrectionSchema.notReady();
        MedicalReviewSchema.Status review;
        try{review=schema.inspectStorageStrict(resource.connection);}
        catch(SQLException|RuntimeException failure){resource.verify();throw MedicalReviewSchema.notReady();}
        resource.verify();
        if(review==MedicalReviewSchema.Status.INCOMPATIBLE)throw new MetadataUnavailable(503,"MEDICAL_REVIEW_SCHEMA_NOT_READY");
        return new MedicalReviewQualificationRepository(resource,parent9,review).collect(ids);
    }

    /** Exact eager holder captured without asking a lazy/custom handle to acquire anything. */
    final class Resource {
        final Thread thread=Thread.currentThread();
        final ConnectionHolder holder; final SimpleConnectionHandle handle; final Connection connection; final int isolation;
        Resource()throws SQLException{
            graph();if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())throw MedicalReviewSchema.notReady();
            Object value=TransactionSynchronizationManager.getResource(dataSource);
            if(value==null||value.getClass()!=ConnectionHolder.class)throw MedicalReviewSchema.notReady();holder=(ConnectionHolder)value;
            if(!holder.isSynchronizedWithTransaction()||holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw MedicalReviewSchema.notReady();
            handle=(SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();
            if(connection==null)throw MedicalReviewSchema.notReady();isolation=connection.getTransactionIsolation();verify();
        }
        void verify()throws SQLException{
            graph();
            if(Thread.currentThread()!=thread||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                    ||TransactionSynchronizationManager.getResource(dataSource)!=holder||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                    ||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw MedicalReviewSchema.notReady();
            try{if(holder.getConnection()!=connection)throw MedicalReviewSchema.notReady();}catch(IllegalStateException failure){throw MedicalReviewSchema.notReady();}
            if(connection.isClosed()||connection.getAutoCommit()||connection.getTransactionIsolation()!=isolation
                    ||isolation!=Connection.TRANSACTION_READ_COMMITTED&&isolation!=Connection.TRANSACTION_REPEATABLE_READ)throw MedicalReviewSchema.notReady();
        }
        void timeout(PreparedStatement statement)throws SQLException{statement.setQueryTimeout(holder.hasTimeout()?Math.min(5,holder.getTimeToLiveInSeconds()):5);}
    }
}
