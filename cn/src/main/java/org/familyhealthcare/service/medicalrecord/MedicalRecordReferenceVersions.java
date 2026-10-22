package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.service.medicalreview.MedicalReviewContracts;
import org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader;
import org.familyhealthcare.service.medicalreview.MedicalReviewSchema;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

/** Three closed native anchors. Read provenance never grants access or mutation authority. */
@Component
public class MedicalRecordReferenceVersions {
    public enum Kind { CARE_PLAN_EVIDENCE, ALERT_EVIDENCE, LEGACY_ALERT }
    @Autowired private org.familyhealthcare.service.careplan.CarePlanAuthorizationService careAuthorization;
    @Autowired private org.familyhealthcare.service.alertworkflow.AlertWorkflowAuthorizationService alertAuthorization;
    private final JdbcTemplate jdbc;
    private final MedicalRecordValidityProjection validity;
    private final DataSource dataSource;
    private final DataSourceTransactionManager transactions;

    public MedicalRecordReferenceVersions(JdbcTemplate jdbc,MedicalRecordValidityProjection validity) {
        this(jdbc,validity,manager(jdbc));
    }
    @Autowired
    public MedicalRecordReferenceVersions(JdbcTemplate jdbc,MedicalRecordValidityProjection validity,DataSourceTransactionManager transactions) {
        if(jdbc==null||jdbc.getDataSource()==null||validity==null||transactions==null)throw MedicalReviewSchema.notReady();
        this.jdbc=jdbc;this.validity=validity;this.dataSource=jdbc.getDataSource();this.transactions=transactions;graph();
    }
    private static DataSourceTransactionManager manager(JdbcTemplate jdbc) {
        if(jdbc==null||jdbc.getDataSource()==null)throw MedicalReviewSchema.notReady();
        return new DataSourceTransactionManager(jdbc.getDataSource());
    }
    private void graph(){if(jdbc.getDataSource()!=dataSource||transactions.getDataSource()!=dataSource)throw MedicalReviewSchema.notReady();}

    public static final class Anchor {
        public final long id,patient,record;public final Long item;
        public Anchor(long id,long patient,long record,Long item){this.id=id;this.patient=patient;this.record=record;this.item=item;}
    }
    public Map<Long,String> statuses(Kind kind,List<Anchor> anchors,MedicalRecordValidityProjection.Scope scope) {
        return projectStatuses(kind,anchors,scope,Collections.emptyMap());
    }
    /** Supplied legacy or previously observed maps are not freshness capabilities. */
    public Map<Long,String> projectStatuses(Kind kind,List<Anchor> anchors,MedicalRecordValidityProjection.Scope scope,Map<Long,MedicalRecordValidityProjection.State> states) {
        Map<Long,ReferenceSnapshot> snapshots=projectSnapshot(kind,anchors,scope,states);
        Map<Long,String> result=new LinkedHashMap<>();for(Map.Entry<Long,ReferenceSnapshot> row:snapshots.entrySet())result.put(row.getKey(),row.getValue().status);
        return Collections.unmodifiableMap(result);
    }
    public Map<Long,ReferenceSnapshot> projectSnapshot(Kind kind,List<Anchor> anchors,MedicalRecordValidityProjection.Scope scope,Map<Long,MedicalRecordValidityProjection.State> states) {
        if(anchors==null||anchors.size()>100)throw limit();if(anchors.isEmpty())return Collections.emptyMap();
        return readSnapshots(Collections.singletonMap(kind,anchors),scope).snapshots.get(kind);
    }
    public static final class ReferenceSnapshot {
        public final String status,signature,bindingStatus,bindingStore,currentSourceStatus;
        public final MedicalReviewQualificationReader.Semantics qualificationSemantics;
        public final Instant checkedAt;
        private ReferenceSnapshot(String status,String bindingStatus,String store,String sourceStatus,String signature,MedicalReviewQualificationReader.Semantics semantics,Instant checkedAt) {
            this.status=status;this.bindingStatus=bindingStatus;bindingStore=store;currentSourceStatus=sourceStatus;this.signature=signature;
            qualificationSemantics=semantics;this.checkedAt=checkedAt;
        }
    }
    /** Immutable result for one finite invocation, never a reusable source-proof token. */
    public static final class ReferenceFrame {
        public final Map<Kind,Map<Long,ReferenceSnapshot>> snapshots;
        public final MedicalReviewQualificationReader.Semantics qualificationSemantics;
        public final Instant checkedAt;
        private final String signature;
        private ReferenceFrame(Map<Kind,Map<Long,ReferenceSnapshot>> snapshots,MedicalReviewQualificationReader.Semantics semantics,Instant checkedAt,String signature) {
            Map<Kind,Map<Long,ReferenceSnapshot>> copy=new LinkedHashMap<>();
            for(Map.Entry<Kind,Map<Long,ReferenceSnapshot>> entry:snapshots.entrySet())copy.put(entry.getKey(),Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
            this.snapshots=Collections.unmodifiableMap(copy);qualificationSemantics=semantics;this.checkedAt=checkedAt;this.signature=signature;
        }
    }
    /** At most 100 distinct (kind,id) anchors; all sources are deduplicated across kinds. */
    public ReferenceFrame readSnapshots(Map<Kind,List<Anchor>> requested,MedicalRecordValidityProjection.Scope scope) {
        Map<Kind,LinkedHashMap<Long,Anchor>> groups=anchors(requested);
        graph();if(scope==null)throw MedicalReviewSchema.notReady();scope.requireDataSource(dataSource);
        if(TransactionSynchronizationManager.isActualTransactionActive())return observe(groups,scope);
        if(TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.hasResource(dataSource))throw MedicalReviewSchema.notReady();
        TransactionTemplate owner=new TransactionTemplate(transactions);owner.setReadOnly(true);owner.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return owner.execute(status->observe(groups,scope));
    }
    /** Care-only, whole selected set. Observations are never caller-supplied authority. */
    public static final class CareEvidenceReadFrame {
        public final Map<Long,MedicalRecordValidityProjection.State> sources;
        public final Map<Long,ReferenceSnapshot> snapshots;
        public final MedicalReviewQualificationReader.Semantics qualificationSemantics;
        public final Instant checkedAt;
        private final String signature;
        private CareEvidenceReadFrame(Map<Long,MedicalRecordValidityProjection.State> sources,Map<Long,ReferenceSnapshot> snapshots,MedicalReviewQualificationReader.Semantics semantics,Instant checkedAt,String signature) {
            this.sources=Collections.unmodifiableMap(new LinkedHashMap<>(sources));this.snapshots=Collections.unmodifiableMap(new LinkedHashMap<>(snapshots));
            qualificationSemantics=semantics;this.checkedAt=checkedAt;this.signature=signature;
        }
    }
    public CareEvidenceReadFrame readCareEvidenceFrame(List<Anchor> requested,Collection<Long> unboundSourceIds,MedicalRecordValidityProjection.Scope scope) {
        if(requested==null||unboundSourceIds==null)throw changed();
        SortedMap<Long,Anchor> anchors=new TreeMap<>();SortedSet<Long> sources=new TreeSet<>();Map<Long,Long> patients=new HashMap<>();
        for(Anchor input:requested) {
            if(input==null||input.item!=null)throw changed();positive(input.id);positive(input.patient);positive(input.record);
            Anchor anchor=new Anchor(input.id,input.patient,input.record,null),prior=anchors.putIfAbsent(anchor.id,anchor);
            if(prior!=null&&(prior.patient!=anchor.patient||prior.record!=anchor.record))throw changed();
            Long patient=patients.putIfAbsent(anchor.record,anchor.patient);if(patient!=null&&patient!=anchor.patient)throw changed();sources.add(anchor.record);
        }
        for(Long source:unboundSourceIds){if(source==null)throw changed();sources.add(positive(source));}
        graph();if(scope==null)throw MedicalReviewSchema.notReady();scope.requireDataSource(dataSource);
        if(TransactionSynchronizationManager.isActualTransactionActive())return observeCare(anchors,new ArrayList<>(sources),scope);
        if(TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.hasResource(dataSource))throw MedicalReviewSchema.notReady();
        TransactionTemplate owner=new TransactionTemplate(transactions);owner.setReadOnly(true);owner.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return owner.execute(status->observeCare(anchors,new ArrayList<>(sources),scope));
    }
    private CareEvidenceReadFrame observeCare(SortedMap<Long,Anchor> anchors,List<Long> sources,MedicalRecordValidityProjection.Scope scope) {
        try {
            Resource resource=new Resource();CareEvidenceReadFrame previous=null;
            for(int attempt=0;attempt<(resource.isolation==Connection.TRANSACTION_REPEATABLE_READ?1:3);attempt++) {
                scope.requireDataSource(dataSource);CareEvidenceReadFrame current=collectCare(resource,anchors,sources,scope);resource.verify();
                if(resource.isolation==Connection.TRANSACTION_REPEATABLE_READ||previous!=null&&previous.signature.equals(current.signature)){scope.requireDataSource(dataSource);return current;}
                previous=current;
            }
            throw new WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","当前引用资格不可用，请刷新后重试。");
        }catch(SQLException failure){throw MedicalReviewSchema.notReady();}
    }
    private CareEvidenceReadFrame collectCare(Resource resource,SortedMap<Long,Anchor> anchors,List<Long> sources,MedicalRecordValidityProjection.Scope scope)throws SQLException {
        resource.verify();Map<Long,MedicalRecordValidityProjection.State> states=new LinkedHashMap<>();
        MedicalReviewSchema.Status review=null;MedicalRecordCorrectionSchema.Status parent9=null;Map<Long,String> sourceSignatures=new LinkedHashMap<>();
        for(int offset=0;offset<sources.size();offset+=100) {
            List<Long> chunk=sources.subList(offset,Math.min(offset+100,sources.size()));Map<Long,MedicalRecordValidityProjection.State> observed=scope.qualifiedSnapshot(chunk);resource.verify();
            if(observed==null||!observed.keySet().equals(new LinkedHashSet<>(chunk)))throw malformed();
            for(long source:chunk) {
                MedicalRecordValidityProjection.State state=observed.get(source);
                if(state==null||state.id!=source||!state.reviewObserved||state.reviewStorage==null||state.parent9Storage==null||state.signature==null)throw malformed();
                if(review!=null&&(review!=state.reviewStorage||parent9!=state.parent9Storage))throw malformed();
                review=state.reviewStorage;parent9=state.parent9Storage;states.put(source,state);sourceSignatures.put(source,state.signature);
            }
        }
        Map<Long,Map<String,Object>> actual=new LinkedHashMap<>(),old=new LinkedHashMap<>(),current=new LinkedHashMap<>();List<Long> ids=new ArrayList<>(anchors.keySet());SortedSet<GenerationKey> closureKeys=new TreeSet<>();
        for(int offset=0;offset<ids.size();offset+=100) {
            Set<Long> chunk=new LinkedHashSet<>(ids.subList(offset,Math.min(offset+100,ids.size())));
            actual.putAll(indexed(resource.query(readAnchorSql(Kind.CARE_PLAN_EVIDENCE)+" WHERE v.id IN ("+marks(chunk.size())+") ORDER BY v.id LIMIT "+(chunk.size()+1),new ArrayList<>(chunk),chunk.size()+1,7),chunk,"id"));
            if(parent9==MedicalRecordCorrectionSchema.Status.READY)old.putAll(bindings(resource,Kind.CARE_PLAN_EVIDENCE,chunk,true));
            if(review==MedicalReviewSchema.Status.READY)current.putAll(bindings(resource,Kind.CARE_PLAN_EVIDENCE,chunk,false));
        }
        for(Anchor anchor:anchors.values()) {
            Map<String,Object> nativeRow=actual.get(anchor.id),binding=current.get(anchor.id);MedicalRecordValidityProjection.State state=states.get(anchor.record);
            if(nativeRow!=null)validateNative(Kind.CARE_PLAN_EVIDENCE,nativeRow);
            if(binding!=null&&old.get(anchor.id)==null&&matchesNative(nativeRow,anchor)&&available(state,anchor)) {
                validateBinding(Kind.CARE_PLAN_EVIDENCE,binding,false);if(!matchesBinding(binding,anchor))continue;
                String generation=(String)binding.get("source_generation");if(!generation.equals(state.liveGeneration))closureKeys.add(new GenerationKey(anchor.record,generation));
            }
        }
        Map<GenerationKey,Map<String,Object>> closures=new LinkedHashMap<>();List<GenerationKey> keys=new ArrayList<>(closureKeys);
        for(int offset=0;offset<keys.size();offset+=100)closures.putAll(closures(resource,keys.subList(offset,Math.min(offset+100,keys.size()))));
        MedicalReviewQualificationReader.Semantics semantics=resource.isolation==Connection.TRANSACTION_REPEATABLE_READ?MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT:MedicalReviewQualificationReader.Semantics.CURRENT_CHECK;
        Instant checkedAt=Instant.now();Map<Long,ReferenceSnapshot> result=new LinkedHashMap<>();Map<String,Object> signatures=new LinkedHashMap<>();
        signatures.put("requestedSources",sources);signatures.put("sources",sourceSignatures);
        for(Anchor anchor:anchors.values()) {
            Map<String,Object> nativeRow=actual.get(anchor.id),oldRow=old.get(anchor.id),newRow=current.get(anchor.id);MedicalRecordValidityProjection.State state=states.get(anchor.record);
            Map<String,Object> closure=newRow==null||!(newRow.get("source_generation") instanceof String)?null:closures.get(new GenerationKey(anchor.record,(String)newRow.get("source_generation")));
            String signature=WorkflowData.json(WorkflowData.map("kind",Kind.CARE_PLAN_EVIDENCE.name(),"anchor",nativeRow,"parent9Binding",oldRow,"reviewBinding",newRow,"source",state.signature,"closure",closure));
            result.put(anchor.id,compose(Kind.CARE_PLAN_EVIDENCE,anchor,nativeRow,oldRow,newRow,state,closure,signature,semantics,checkedAt));
            signatures.put("CARE_PLAN_EVIDENCE:"+anchor.id,WorkflowData.map("patient",anchor.patient,"source",anchor.record,"observation",signature));
        }
        resource.verify();return new CareEvidenceReadFrame(states,result,semantics,checkedAt,WorkflowData.json(signatures));
    }
    /** LEGACY_ALERT-only whole fixed selection; source work is deduplicated before collection. */
    public ReferenceFrame readLegacyAlertFrame(List<Anchor> requested,MedicalRecordValidityProjection.Scope scope) {
        if(requested==null)throw changed();
        SortedMap<Long,Anchor> anchors=new TreeMap<>();SortedSet<Long> sources=new TreeSet<>();Map<Long,Long> patients=new HashMap<>();
        for(Anchor input:requested) {
            if(input==null||input.item==null)throw changed();positive(input.id);positive(input.patient);positive(input.record);positive(input.item);
            Anchor anchor=new Anchor(input.id,input.patient,input.record,input.item),prior=anchors.putIfAbsent(anchor.id,anchor);
            if(prior!=null&&(prior.patient!=anchor.patient||prior.record!=anchor.record||!Objects.equals(prior.item,anchor.item)))throw changed();
            Long patient=patients.putIfAbsent(anchor.record,anchor.patient);if(patient!=null&&patient!=anchor.patient)throw changed();sources.add(anchor.record);
        }
        graph();if(scope==null)throw MedicalReviewSchema.notReady();scope.requireDataSource(dataSource);
        if(TransactionSynchronizationManager.isActualTransactionActive())return observeLegacyAlerts(anchors,new ArrayList<>(sources),scope);
        if(TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.hasResource(dataSource))throw MedicalReviewSchema.notReady();
        TransactionTemplate owner=new TransactionTemplate(transactions);owner.setReadOnly(true);owner.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return owner.execute(status->observeLegacyAlerts(anchors,new ArrayList<>(sources),scope));
    }
    private ReferenceFrame observeLegacyAlerts(SortedMap<Long,Anchor> anchors,List<Long> sources,MedicalRecordValidityProjection.Scope scope) {
        try {
            Resource resource=new Resource();ReferenceFrame previous=null;
            for(int attempt=0;attempt<(resource.isolation==Connection.TRANSACTION_REPEATABLE_READ?1:3);attempt++) {
                scope.requireDataSource(dataSource);ReferenceFrame current=collectLegacyAlerts(resource,anchors,sources,scope);resource.verify();
                if(resource.isolation==Connection.TRANSACTION_REPEATABLE_READ||previous!=null&&previous.signature.equals(current.signature)){scope.requireDataSource(dataSource);return current;}
                previous=current;
            }
            throw new WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","当前引用资格不可用，请刷新后重试。");
        }catch(SQLException failure){throw MedicalReviewSchema.notReady();}
    }
    private ReferenceFrame collectLegacyAlerts(Resource resource,SortedMap<Long,Anchor> anchors,List<Long> sources,MedicalRecordValidityProjection.Scope scope)throws SQLException {
        resource.verify();Map<Long,MedicalRecordValidityProjection.State> states=new LinkedHashMap<>();
        MedicalReviewSchema.Status review=null;MedicalRecordCorrectionSchema.Status parent9=null;Map<Long,String> sourceSignatures=new LinkedHashMap<>();
        for(int offset=0;offset<sources.size();offset+=100) {
            List<Long> chunk=sources.subList(offset,Math.min(offset+100,sources.size()));Map<Long,MedicalRecordValidityProjection.State> observed=scope.qualifiedSnapshot(chunk);resource.verify();
            if(observed==null||!observed.keySet().equals(new LinkedHashSet<>(chunk)))throw malformed();
            for(long source:chunk) {
                MedicalRecordValidityProjection.State state=observed.get(source);
                if(state==null||state.id!=source||!state.reviewObserved||state.reviewStorage==null||state.parent9Storage==null||state.signature==null)throw malformed();
                if(review!=null&&(review!=state.reviewStorage||parent9!=state.parent9Storage))throw malformed();
                review=state.reviewStorage;parent9=state.parent9Storage;states.put(source,state);sourceSignatures.put(source,state.signature);
            }
        }
        Map<Long,Map<String,Object>> actual=new LinkedHashMap<>(),old=new LinkedHashMap<>(),current=new LinkedHashMap<>();List<Long> ids=new ArrayList<>(anchors.keySet());SortedSet<GenerationKey> closureKeys=new TreeSet<>();
        for(int offset=0;offset<ids.size();offset+=100) {
            Set<Long> chunk=new LinkedHashSet<>(ids.subList(offset,Math.min(offset+100,ids.size())));
            actual.putAll(indexed(resource.query(readAnchorSql(Kind.LEGACY_ALERT)+" WHERE v.id IN ("+marks(chunk.size())+") ORDER BY v.id LIMIT "+(chunk.size()+1),new ArrayList<>(chunk),chunk.size()+1,7),chunk,"id"));
            if(parent9==MedicalRecordCorrectionSchema.Status.READY)old.putAll(bindings(resource,Kind.LEGACY_ALERT,chunk,true));
            if(review==MedicalReviewSchema.Status.READY)current.putAll(bindings(resource,Kind.LEGACY_ALERT,chunk,false));
        }
        for(Anchor anchor:anchors.values()) {
            Map<String,Object> nativeRow=actual.get(anchor.id),binding=current.get(anchor.id);MedicalRecordValidityProjection.State state=states.get(anchor.record);
            if(nativeRow!=null)validateNative(Kind.LEGACY_ALERT,nativeRow);
            if(binding!=null&&old.get(anchor.id)==null&&matchesNative(nativeRow,anchor)&&available(state,anchor)) {
                validateBinding(Kind.LEGACY_ALERT,binding,false);if(!matchesBinding(binding,anchor))continue;
                String generation=(String)binding.get("source_generation");if(!generation.equals(state.liveGeneration))closureKeys.add(new GenerationKey(anchor.record,generation));
            }
        }
        Map<GenerationKey,Map<String,Object>> closures=new LinkedHashMap<>();List<GenerationKey> keys=new ArrayList<>(closureKeys);
        for(int offset=0;offset<keys.size();offset+=100)closures.putAll(closures(resource,keys.subList(offset,Math.min(offset+100,keys.size()))));
        MedicalReviewQualificationReader.Semantics semantics=resource.isolation==Connection.TRANSACTION_REPEATABLE_READ?MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT:MedicalReviewQualificationReader.Semantics.CURRENT_CHECK;
        Instant checkedAt=Instant.now();Map<Long,ReferenceSnapshot> result=new LinkedHashMap<>();Map<String,Object> signatures=new LinkedHashMap<>();
        signatures.put("requestedSources",sources);signatures.put("sources",sourceSignatures);
        for(Anchor anchor:anchors.values()) {
            Map<String,Object> nativeRow=actual.get(anchor.id),oldRow=old.get(anchor.id),newRow=current.get(anchor.id);MedicalRecordValidityProjection.State state=states.get(anchor.record);
            Map<String,Object> closure=newRow==null||!(newRow.get("source_generation") instanceof String)?null:closures.get(new GenerationKey(anchor.record,(String)newRow.get("source_generation")));
            String signature=WorkflowData.json(WorkflowData.map("kind",Kind.LEGACY_ALERT.name(),"anchor",nativeRow,"parent9Binding",oldRow,"reviewBinding",newRow,"source",state.signature,"closure",closure));
            result.put(anchor.id,compose(Kind.LEGACY_ALERT,anchor,nativeRow,oldRow,newRow,state,closure,signature,semantics,checkedAt));
            signatures.put("LEGACY_ALERT:"+anchor.id,WorkflowData.map("patient",anchor.patient,"source",anchor.record,"item",anchor.item,"observation",signature));
        }
        resource.verify();return new ReferenceFrame(Collections.singletonMap(Kind.LEGACY_ALERT,result),semantics,checkedAt,WorkflowData.json(signatures));
    }
    private Map<Kind,LinkedHashMap<Long,Anchor>> anchors(Map<Kind,List<Anchor>> requested) {
        if(requested==null||requested.containsKey(null))throw changed();
        Map<Kind,LinkedHashMap<Long,Anchor>> result=new LinkedHashMap<>();int count=0;
        for(Kind kind:Kind.values())if(requested.containsKey(kind)) {
            List<Anchor> input=requested.get(kind);if(input==null)throw changed();LinkedHashMap<Long,Anchor> group=new LinkedHashMap<>();
            for(Anchor anchor:input) {
                if(anchor==null)throw changed();positive(anchor.id);positive(anchor.patient);positive(anchor.record);if(anchor.item!=null)positive(anchor.item);
                if((kind==Kind.LEGACY_ALERT)!=(anchor.item!=null))throw changed();
                Anchor prior=group.putIfAbsent(anchor.id,anchor);
                if(prior!=null&&(prior.patient!=anchor.patient||prior.record!=anchor.record||!Objects.equals(prior.item,anchor.item)))throw changed();
                if(prior==null&&++count>100)throw limit();
            }
            result.put(kind,group);
        }
        return result;
    }
    private ReferenceFrame observe(Map<Kind,LinkedHashMap<Long,Anchor>> groups,MedicalRecordValidityProjection.Scope scope) {
        try {
            Resource resource=new Resource();ReferenceFrame previous=null;
            for(int attempt=0;attempt<(resource.isolation==Connection.TRANSACTION_REPEATABLE_READ?1:3);attempt++) {
                scope.requireDataSource(dataSource);ReferenceFrame current=collect(resource,groups,scope);resource.verify();
                if(resource.isolation==Connection.TRANSACTION_REPEATABLE_READ||previous!=null&&previous.signature.equals(current.signature)){scope.requireDataSource(dataSource);return current;}
                previous=current;
            }
            throw new WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","Current reference qualification is unavailable. Reload and try again.");
        }catch(SQLException failure){throw MedicalReviewSchema.notReady();}
    }
    private ReferenceFrame collect(Resource resource,Map<Kind,LinkedHashMap<Long,Anchor>> groups,MedicalRecordValidityProjection.Scope scope)throws SQLException {
        resource.verify();SortedSet<Long> sources=new TreeSet<>();for(Map<Long,Anchor> group:groups.values())for(Anchor anchor:group.values())sources.add(anchor.record);
        Map<Long,MedicalRecordValidityProjection.State> states=sources.isEmpty()?Collections.emptyMap():scope.qualifiedSnapshot(sources);resource.verify();
        MedicalReviewSchema.Status review=null;MedicalRecordCorrectionSchema.Status parent9=null;
        Map<Long,String> sourceSignatures=new LinkedHashMap<>();
        for(long source:sources) {
            MedicalRecordValidityProjection.State state=states.get(source);
            if(state==null||state.id!=source||!state.reviewObserved||state.reviewStorage==null||state.parent9Storage==null)throw malformed();
            if(review!=null&&(review!=state.reviewStorage||parent9!=state.parent9Storage))throw malformed();
            review=state.reviewStorage;parent9=state.parent9Storage;sourceSignatures.put(source,state.signature);
        }
        Map<Kind,Map<Long,Map<String,Object>>> actual=new LinkedHashMap<>(),old=new LinkedHashMap<>(),current=new LinkedHashMap<>();
        SortedSet<GenerationKey> closureKeys=new TreeSet<>();
        for(Map.Entry<Kind,LinkedHashMap<Long,Anchor>> entry:groups.entrySet()) {
            Kind kind=entry.getKey();Set<Long> ids=entry.getValue().keySet();
            Map<Long,Map<String,Object>> nativeRows=ids.isEmpty()?Collections.emptyMap():indexed(resource.query(readAnchorSql(kind)+" WHERE v.id IN ("+marks(ids.size())+") ORDER BY v.id LIMIT "+(ids.size()+1),new ArrayList<>(ids),ids.size()+1,7),ids,"id");
            Map<Long,Map<String,Object>> oldRows=ids.isEmpty()||parent9!=MedicalRecordCorrectionSchema.Status.READY?Collections.emptyMap():bindings(resource,kind,ids,true);
            Map<Long,Map<String,Object>> newRows=ids.isEmpty()||review!=MedicalReviewSchema.Status.READY?Collections.emptyMap():bindings(resource,kind,ids,false);
            actual.put(kind,nativeRows);old.put(kind,oldRows);current.put(kind,newRows);
            for(Anchor anchor:entry.getValue().values()) {
                Map<String,Object> nativeRow=nativeRows.get(anchor.id),binding=newRows.get(anchor.id);
                MedicalRecordValidityProjection.State state=states.get(anchor.record);
                if(nativeRow!=null)validateNative(kind,nativeRow);
                if(binding!=null&&oldRows.get(anchor.id)==null&&matchesNative(nativeRow,anchor)&&available(state,anchor)) {
                    validateBinding(kind,binding,false);
                    if(!matchesBinding(binding,anchor))continue;
                    String generation=(String)binding.get("source_generation");
                    if(!generation.equals(state.liveGeneration))closureKeys.add(new GenerationKey(anchor.record,generation));
                }
            }
        }
        Map<GenerationKey,Map<String,Object>> closures=closures(resource,new ArrayList<>(closureKeys));
        MedicalReviewQualificationReader.Semantics semantics=resource.isolation==Connection.TRANSACTION_REPEATABLE_READ?MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT:MedicalReviewQualificationReader.Semantics.CURRENT_CHECK;
        Instant checkedAt=Instant.now();Map<Kind,Map<Long,ReferenceSnapshot>> result=new LinkedHashMap<>();
        Map<String,Object> signatures=new LinkedHashMap<>();signatures.put("sources",sourceSignatures);
        for(Map.Entry<Kind,LinkedHashMap<Long,Anchor>> entry:groups.entrySet()) {
            Kind kind=entry.getKey();Map<Long,ReferenceSnapshot> rows=new LinkedHashMap<>();
            for(Anchor anchor:entry.getValue().values()) {
                Map<String,Object> nativeRow=actual.get(kind).get(anchor.id),oldRow=old.get(kind).get(anchor.id),newRow=current.get(kind).get(anchor.id);
                MedicalRecordValidityProjection.State state=states.get(anchor.record);
                Map<String,Object> closure=newRow==null||!(newRow.get("source_generation") instanceof String)?null:closures.get(new GenerationKey(anchor.record,(String)newRow.get("source_generation")));
                String signature=WorkflowData.json(WorkflowData.map("kind",kind.name(),"anchor",nativeRow,"parent9Binding",oldRow,"reviewBinding",newRow,"source",state==null?null:state.signature,"closure",closure));
                rows.put(anchor.id,compose(kind,anchor,nativeRow,oldRow,newRow,state,closure,signature,semantics,checkedAt));
                signatures.put(kind.name()+":"+anchor.id,signature);
            }
            result.put(kind,rows);
        }
        resource.verify();return new ReferenceFrame(result,semantics,checkedAt,WorkflowData.json(signatures));
    }
    private ReferenceSnapshot compose(Kind kind,Anchor anchor,Map<String,Object> actual,Map<String,Object> old,Map<String,Object> current,MedicalRecordValidityProjection.State state,Map<String,Object> closure,String signature,MedicalReviewQualificationReader.Semantics semantics,Instant checkedAt) {
        String store=old!=null&&current!=null?"CONFLICT":old!=null?"PARENT9":current!=null?"REVIEW":"NONE";
        String sourceStatus=state==null?"SOURCE_UNAVAILABLE":state.status();String binding,status;
        if(!matchesNative(actual,anchor)){binding="MISSING_ANCHOR";status="SOURCE_UNAVAILABLE";sourceStatus=status;}
        else if(!available(state,anchor)){binding="SOURCE_MISSING";status="SOURCE_UNAVAILABLE";sourceStatus=status;}
        else if(old!=null&&current!=null){binding="STORE_CONFLICT";status="SOURCE_UNAVAILABLE";}
        else if(old==null&&current==null){binding="UNBOUND";status=!state.eligible()?sourceStatus:!state.managed&&!state.reviewParticipating?"UNTRACKED":"UNKNOWN_NEEDS_RECHECK";}
        else {
            Map<String,Object> row=old!=null?old:current;validateBinding(kind,row,old!=null);
            if(!matchesBinding(row,anchor)||old!=null&&!state.managed)throw malformed();
            long version=positive(row.get("content_version"));
            if(old!=null) {
                if(version>state.version)throw malformed();binding=version==state.version?"CURRENT_CONTENT":"CONTENT_CHANGED";
                status=!state.eligible()?sourceStatus:version==state.version?"CURRENT":"SOURCE_CHANGED";
            }else if(!Objects.equals(row.get("source_generation"),state.liveGeneration)) {
                if(closure==null||positive(closure.get("content_version"))<version)throw malformed();
                binding="GENERATION_CHANGED";status="SOURCE_CHANGED";
            }else {
                if(version>state.version)throw malformed();binding=version==state.version?"CURRENT_CONTENT":"CONTENT_CHANGED";
                status=version!=state.version?"SOURCE_CHANGED":state.eligible()?"CURRENT":sourceStatus;
            }
        }
        return new ReferenceSnapshot(status,binding,store,sourceStatus,signature,semantics,checkedAt);
    }
    private static boolean available(MedicalRecordValidityProjection.State state,Anchor anchor){return state!=null&&state.reviewObserved&&state.version!=null&&!"SOURCE_UNAVAILABLE".equals(state.status())&&Objects.equals(state.patientId,anchor.patient);}
    private static boolean matchesNative(Map<String,Object> row,Anchor anchor){return row!=null&&positive(row.get("patient_id"))==anchor.patient&&positive(row.get("record_id"))==anchor.record&&Objects.equals(nullable(row.get("item_id")),anchor.item);}
    private static boolean matchesBinding(Map<String,Object> row,Anchor anchor){return row!=null&&positive(row.get("patient_id"))==anchor.patient&&positive(row.get("record_id"))==anchor.record&&Objects.equals(nullable(row.get("source_item_id")),anchor.item);}
    private static void validateNative(Kind kind,Map<String,Object> row) {
        positive(row.get("id"));positive(row.get("patient_id"));positive(row.get("record_id"));positive(row.get("aggregate_id"));
        if(kind==Kind.LEGACY_ALERT){positive(row.get("item_id"));if(row.get("event_id")!=null||row.get("action_id")!=null)throw malformed();}
        else {positive(row.get("event_id"));if(row.get("item_id")!=null)throw malformed();if(kind==Kind.CARE_PLAN_EVIDENCE)positive(row.get("action_id"));else if(row.get("action_id")!=null)throw malformed();}
    }
    private static void validateBinding(Kind kind,Map<String,Object> row,boolean old) {
        if(old)positive(row.get("id"));positive(row.get("reference_id"));positive(row.get("patient_id"));positive(row.get("record_id"));positive(row.get("content_version"));
        if(!kind.name().equals(row.get("reference_kind")))throw malformed();
        if(kind==Kind.LEGACY_ALERT)positive(row.get("source_item_id"));else if(row.get("source_item_id")!=null)throw malformed();
        if(!old)generation(row.get("source_generation"));timestamp(row,"bound_at");
    }
    private Map<Long,Map<String,Object>> bindings(Resource resource,Kind kind,Set<Long> ids,boolean old)throws SQLException {
        String columns=old?"id,reference_kind,reference_id,patient_id,record_id,source_item_id,content_version":"reference_kind,reference_id,patient_id,record_id,source_generation,source_item_id,content_version";
        String table=old?"medical_record_reference_version FORCE INDEX (mrc57_reference_anchor)":"medical_review_reference FORCE INDEX (PRIMARY)";
        List<Object> args=new ArrayList<>();args.add(kind.name());args.addAll(ids);
        return indexed(resource.query("SELECT "+columns+",DATE_FORMAT(bound_at,'%Y-%m-%dT%H:%i:%s.%f') AS bound_at_text,bound_at IS NULL AS bound_at_null FROM "+table+" WHERE reference_kind=? AND reference_id IN ("+marks(ids.size())+") ORDER BY reference_id LIMIT "+(ids.size()+1),args,ids.size()+1,9),ids,"reference_id");
    }
    private static Map<Long,Map<String,Object>> indexed(List<Map<String,Object>> rows,Set<Long> ids,String key) {
        if(rows.size()>ids.size())throw malformed();Map<Long,Map<String,Object>> result=new LinkedHashMap<>();long previous=0;
        for(Map<String,Object> row:rows){long id=positive(row.get(key));if(!ids.contains(id)||id<=previous||result.put(id,row)!=null)throw malformed();previous=id;}
        return result;
    }
    private static final class GenerationKey implements Comparable<GenerationKey> {
        final long record;final String generation;
        GenerationKey(long record,String generation){this.record=record;this.generation=generation;}
        public int compareTo(GenerationKey other){int compared=Long.compare(record,other.record);return compared!=0?compared:generation.compareTo(other.generation);}
        @Override public boolean equals(Object other){return other instanceof GenerationKey&&compareTo((GenerationKey)other)==0;}
        @Override public int hashCode(){return Objects.hash(record,generation);}
    }
    private Map<GenerationKey,Map<String,Object>> closures(Resource resource,List<GenerationKey> keys)throws SQLException {
        if(keys.isEmpty())return Collections.emptyMap();
        String event="e.id,e.record_id,e.source_generation,e.channel,e.operation,e.before_content_version,e.content_version,e.decision_version,e.cycle,e.state,e.actor_id,e.actor_kind,DATE_FORMAT(e.occurred_at,'%Y-%m-%dT%H:%i:%s.%f') AS occurred_at_text,e.occurred_at IS NULL AS occurred_at_null";
        String table="("+String.join(" UNION ALL ",Collections.nCopies(keys.size(),"SELECT ? AS record_id,? AS generation"))+") k";
        List<Object> args=new ArrayList<>();for(GenerationKey key:keys){args.add(key.record);args.add(key.generation);}
        List<Map<String,Object>> rows=resource.query("SELECT k.record_id AS requested_record_id,k.generation AS requested_generation,"+event+" FROM "+table+" LEFT JOIN medical_review_event e FORCE INDEX (mrv57_event_version) ON e.record_id=k.record_id AND e.source_generation=k.generation AND e.channel='RECORD' AND e.decision_version=0 ORDER BY k.record_id,k.generation LIMIT "+(keys.size()+1),args,keys.size()+1,16);
        if(rows.size()!=keys.size())throw malformed();Map<GenerationKey,Map<String,Object>> result=new LinkedHashMap<>();
        for(int i=0;i<keys.size();i++) {
            GenerationKey key=keys.get(i);Map<String,Object> row=rows.get(i);
            if(positive(row.get("requested_record_id"))!=key.record||!key.generation.equals(row.get("requested_generation")))throw malformed();
            if(row.get("id")==null){for(Map.Entry<String,Object> field:row.entrySet())if(!field.getKey().startsWith("requested_")&&!"occurred_at_null".equals(field.getKey())&&field.getValue()!=null)throw malformed();if(nonnegative(row.get("occurred_at_null"))!=1)throw malformed();result.put(key,null);continue;}
            positive(row.get("id"));positive(row.get("actor_id"));
            if(positive(row.get("record_id"))!=key.record||!key.generation.equals(row.get("source_generation"))||!"RECORD".equals(row.get("channel"))||!"SOURCE_DELETED".equals(row.get("operation"))||!"DELETED".equals(row.get("state"))||!Arrays.asList("OWNER","DELEGATE","ADMIN","ASSIGNED_DOCTOR").contains(row.get("actor_kind")))throw malformed();
            long version=positive(row.get("content_version"));
            try{MedicalReviewContracts.requireEventFacts(key.generation,MedicalReviewContracts.Channel.RECORD,MedicalReviewContracts.Operation.SOURCE_DELETED,MedicalReviewContracts.State.DELETED,nullable(row.get("before_content_version")),version,nonnegative(row.get("decision_version")),nonnegative(row.get("cycle")));}catch(WorkflowException bad){throw malformed();}
            timestamp(row,"occurred_at");result.put(key,row);
        }
        return result;
    }
    private static String generation(Object value){if(!(value instanceof String)||!((String)value).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw malformed();return(String)value;}
    private static void timestamp(Map<String,Object> row,String name) {
        if(nonnegative(row.get(name+"_null"))!=0||!(row.get(name+"_text") instanceof String))throw malformed();String value=(String)row.get(name+"_text");
        if(!value.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}"))throw malformed();
        try{LocalDateTime.parse(value,DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS").withResolverStyle(ResolverStyle.STRICT));}catch(java.time.DateTimeException invalid){throw malformed();}
    }
    private static Long nullable(Object value){return value==null?null:positive(value);}
    private static long positive(Object value){long number=nonnegative(value);if(number==0)throw malformed();return number;}
    private static long nonnegative(Object value) {
        long number;try{if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)number=((Number)value).longValue();else if(value instanceof BigInteger)number=((BigInteger)value).longValueExact();else if(value instanceof BigDecimal)number=((BigDecimal)value).longValueExact();else throw malformed();}catch(ArithmeticException invalid){throw malformed();}
        if(number<0||number>MedicalReviewContracts.MAX_ID)throw malformed();return number;
    }
    private static WorkflowException malformed(){return new WorkflowException(409,"MEDICAL_REVIEW_INTEGRITY","Medical record reference provenance is inconsistent.");}
    private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
    private String readAnchorSql(Kind kind) {
        switch(kind) {
            case CARE_PLAN_EVIDENCE:return "SELECT v.id,p.patient_id,v.source_id AS record_id,NULL AS item_id,e.id AS event_id,p.id AS aggregate_id,a.id AS action_id FROM care_plan_evidence v FORCE INDEX (PRIMARY) JOIN care_plan_event e ON e.id=v.event_id JOIN doctor_care_plan p ON p.id=e.plan_id AND p.patient_id=e.patient_id JOIN care_plan_action a ON a.id=e.action_id AND a.plan_id=p.id AND a.patient_id=p.patient_id AND v.source_type='MEDICAL_RECORD'";
            case ALERT_EVIDENCE:return "SELECT v.id,a.patient_id,v.source_id AS record_id,NULL AS item_id,e.id AS event_id,a.id AS aggregate_id,NULL AS action_id FROM alert_evidence v FORCE INDEX (PRIMARY) JOIN alert_event e ON e.id=v.event_id JOIN alert_record a ON a.id=e.alert_id AND a.patient_id=e.patient_id AND a.workflow_version=1 AND v.source_type='MEDICAL_RECORD'";
            case LEGACY_ALERT:return "SELECT v.id,v.patient_id,i.record_id,i.id AS item_id,NULL AS event_id,v.id AS aggregate_id,NULL AS action_id FROM alert_record v FORCE INDEX (PRIMARY) JOIN medical_record_item i ON i.id=v.source_id AND v.source_type='MEDICAL_RECORD_ITEM' AND (v.alert_type IS NULL OR v.alert_type<>'MEASUREMENT_FOLLOW_UP')";
            default:throw changed();
        }
    }
    /** Capture the actual eager owner; never repair, replace or suspend it. */
    private final class Resource {
        final Thread thread=Thread.currentThread();final ConnectionHolder holder;final SimpleConnectionHandle handle;final Connection connection;final int isolation;
        Resource()throws SQLException {
            graph();if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())throw MedicalReviewSchema.notReady();
            Object value=TransactionSynchronizationManager.getResource(dataSource);if(value==null||value.getClass()!=ConnectionHolder.class)throw MedicalReviewSchema.notReady();holder=(ConnectionHolder)value;
            if(!holder.isSynchronizedWithTransaction()||holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw MedicalReviewSchema.notReady();
            handle=(SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();if(connection==null)throw MedicalReviewSchema.notReady();isolation=connection.getTransactionIsolation();verify();
        }
        void verify()throws SQLException {
            graph();if(Thread.currentThread()!=thread||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.getResource(dataSource)!=holder||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw MedicalReviewSchema.notReady();
            try{if(holder.getConnection()!=connection)throw MedicalReviewSchema.notReady();}catch(IllegalStateException invalid){throw MedicalReviewSchema.notReady();}
            if(connection.isClosed()||connection.getAutoCommit()||connection.getTransactionIsolation()!=isolation||isolation!=Connection.TRANSACTION_READ_COMMITTED&&isolation!=Connection.TRANSACTION_REPEATABLE_READ)throw MedicalReviewSchema.notReady();
        }
        List<Map<String,Object>> query(String sql,List<?> args,int limit,int columns)throws SQLException {
            verify();List<Map<String,Object>> rows=new ArrayList<>();
            try(PreparedStatement statement=connection.prepareStatement(sql)) {
                statement.setQueryTimeout(holder.hasTimeout()?Math.min(5,holder.getTimeToLiveInSeconds()):5);for(int i=0;i<args.size();i++)statement.setObject(i+1,args.get(i));
                try(ResultSet result=statement.executeQuery()) {
                    ResultSetMetaData meta=result.getMetaData();if(meta.getColumnCount()!=columns)throw malformed();
                    while(result.next()) {
                        if(rows.size()>=limit)throw malformed();Map<String,Object> row=new LinkedHashMap<>();
                        for(int i=1;i<=columns;i++){String label=meta.getColumnLabel(i);if(row.containsKey(label))throw malformed();Object value=result.getObject(i);if(value instanceof Boolean&&Arrays.asList("bound_at_null","occurred_at_null").contains(label))value=Boolean.TRUE.equals(value)?1L:0L;row.put(label,value);}
                        rows.add(Collections.unmodifiableMap(row));
                    }
                }
            }
            verify();return rows;
        }
    }
    /** One event's already-authorized medical sources. Construction never accepts an authority token. */
    public CareEvidenceFrame prepareCareEvidence(long event,long patient,long actor,Collection<Long> sources,MedicalRecordValidityProjection.Scope scope) {
        try{return new CareEvidenceFrame(new EvidenceProof(Kind.CARE_PLAN_EVIDENCE,event,patient,actor,sources,scope));}
        catch(SQLException|org.springframework.dao.DataAccessException failure){throw MedicalReviewSchema.notReady();}
    }
    public final class CareEvidenceFrame implements AutoCloseable {
        private final EvidenceProof proof;
        private CareEvidenceFrame(EvidenceProof proof){this.proof=proof;}
        public long insert(long source){return proof.insert(source,null,null,null);}
        public void complete(){proof.complete();}
        @Override public void close(){proof.guard.close();}
    }
    public long insertCareEvidence(long event,long patient,String type,long source,MedicalRecordValidityProjection.Scope scope) {
        if(!"MEDICAL_RECORD".equals(type)){WorkflowData.mutationTransaction(jdbc);return insert("INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(?,?,?)",event,type,source);}
        try(CareEvidenceFrame frame=prepareCareEvidence(event,patient,currentActor(),Collections.singleton(source),scope)) {
            long id=frame.insert(source);frame.complete();return id;
        }
    }
    /** One alert event's fixed medical insertion proof; no bind-existing capability is exposed. */
    public AlertEvidenceFrame prepareAlertEvidence(long event,long patient,long actor,Collection<Long> sources,MedicalRecordValidityProjection.Scope scope) {
        try{return new AlertEvidenceFrame(new EvidenceProof(Kind.ALERT_EVIDENCE,event,patient,actor,sources,scope));}
        catch(SQLException|org.springframework.dao.DataAccessException failure){throw MedicalReviewSchema.notReady();}
    }
    public final class AlertEvidenceFrame implements AutoCloseable {
        private final EvidenceProof proof;
        private AlertEvidenceFrame(EvidenceProof proof){this.proof=proof;}
        public long insert(long source){return proof.insert(source,null,null,"VALID");}
        public void complete(){proof.complete();}
        @Override public void close(){proof.guard.close();}
    }
    /** Existing alert common seam. Its per-event batching remains the separately gated H2 integration. */
    public long insertAlertEvidence(long event,long patient,String type,long source,Long revision,Long version,String dependency,MedicalRecordValidityProjection.Scope scope) {
        if(!"MEDICAL_RECORD".equals(type)){WorkflowData.mutationTransaction(jdbc);return insert("INSERT INTO alert_evidence(event_id,source_type,source_id,source_revision,dependency_version,dependency_state) VALUES(?,?,?,?,?,?)",event,type,source,revision,version,dependency);}
        EvidenceProof proof;
        try{proof=new EvidenceProof(Kind.ALERT_EVIDENCE,event,patient,currentActor(),Collections.singleton(source),scope);}
        catch(SQLException|org.springframework.dao.DataAccessException failure){throw MedicalReviewSchema.notReady();}
        try{long id=proof.insert(source,revision,version,dependency);proof.complete();return id;}
        finally{proof.guard.close();}
    }
    private static long currentActor(){Long actor=org.familyhealthcare.util.CurrentUserUtil.getCurrentUserId();if(actor==null)throw org.familyhealthcare.service.careplan.CarePlanException.denied();return positive(actor);}
    private void authority(Kind kind,long actor,long patient,Collection<Long> sources) {
        if(currentActor()!=actor)throw org.familyhealthcare.service.careplan.CarePlanException.denied();
        for(long source:sources) {
            boolean allowed;
            if(kind==Kind.CARE_PLAN_EVIDENCE){if(careAuthorization==null)throw MedicalReviewSchema.notReady();allowed=careAuthorization.canReadEvidence(actor,patient,"MEDICAL_RECORD",source);}
            else if(kind==Kind.ALERT_EVIDENCE){if(alertAuthorization==null)throw MedicalReviewSchema.notReady();allowed=alertAuthorization.canReadEvidence(actor,patient,"MEDICAL_RECORD",source);}
            else throw changed();
            if(!allowed)throw kind==Kind.CARE_PLAN_EVIDENCE?org.familyhealthcare.service.careplan.CarePlanException.denied():org.familyhealthcare.service.alertworkflow.AlertWorkflowException.denied();
        }
    }
    /** Internal fixed proof owner, not a caller callback. */
    abstract class WriteProof {
        final Kind kind;final Long actor;final long patient;final ThresholdTarget target;final SortedSet<Long> sources=new TreeSet<>();
        final MedicalRecordValidityProjection.Scope scope;final MedicalRecordReferenceWriteGuard guard;final Resource resource;
        final Map<Long,MedicalRecordValidityProjection.State> expectedSources;
        WriteProof(Kind kind,long patient,long actor,Collection<Long> requested,MedicalRecordValidityProjection.Scope scope,int maximum)throws SQLException {
            this.kind=kind;this.patient=positive(patient);this.actor=positive(actor);this.scope=scope;this.target=null;
            if(requested==null||requested.isEmpty()||requested.size()>maximum)throw limit();
            for(Long source:requested)if(source==null||!sources.add(positive(source)))throw changed();
            graph();if(scope==null)throw MedicalReviewSchema.notReady();scope.requireDataSource(dataSource);
            guard=new MedicalRecordReferenceWriteGuard(jdbc,transactions);resource=new Resource();
            authority(kind,actor,patient,sources);
            for(long source:sources) {
                List<Map<String,Object>> rows=resource.query("SELECT id FROM medical_record WHERE id=? AND patient_id=? FOR UPDATE",Arrays.asList(source,patient),2,1);
                if(rows.size()!=1||positive(rows.get(0).get("id"))!=source)throw changed();
            }
            authority(kind,actor,patient,sources);expectedSources=qualified();guard.verify();
        }
        private WriteProof(ThresholdTarget target,MedicalRecordValidityProjection.Scope scope)throws SQLException {
            this.kind=Kind.LEGACY_ALERT;this.patient=target.patient;this.actor=null;this.target=target;this.scope=scope;sources.add(target.source);
            graph();if(scope==null)throw MedicalReviewSchema.notReady();scope.requireDataSource(dataSource);
            guard=new MedicalRecordReferenceWriteGuard(jdbc,transactions);resource=new Resource();
            List<Map<String,Object>> rows=resource.query("SELECT id FROM medical_record WHERE id=? AND patient_id=? FOR UPDATE",Arrays.asList(target.source,patient),2,1);
            if(rows.size()>1)throw malformed();
            if(rows.isEmpty()){expectedSources=Collections.emptyMap();return;}
            if(positive(rows.get(0).get("id"))!=target.source)throw changed();
            expectedSources=scope.qualifiedSnapshot(sources);MedicalRecordValidityProjection.State state=expectedSources.get(target.source);
            if(state==null||state.id!=target.source||!state.reviewObserved||state.sourceReleaseSignature==null||!Objects.equals(state.patientId,patient)||state.version==null)throw changed();
            guard.verify();scope.requireDataSource(dataSource);
        }
        Map<Long,MedicalRecordValidityProjection.State> qualified()throws SQLException {
            guard.verify();scope.requireDataSource(dataSource);Map<Long,MedicalRecordValidityProjection.State> states=scope.qualifiedSnapshot(sources);
            for(long source:sources){MedicalRecordValidityProjection.State state=states.get(source);if(state==null||state.id!=source||!state.reviewObserved||!Objects.equals(state.patientId,patient)||state.version==null||!state.eligible()||state.sourceReleaseSignature==null)throw changed();}
            guard.verify();return states;
        }
        void verifySources()throws SQLException {
            Map<Long,MedicalRecordValidityProjection.State> current=qualified();
            for(long source:sources)if(!expectedSources.get(source).sourceReleaseSignature.equals(current.get(source).sourceReleaseSignature))throw changed();
            if(target==null)authority(kind,actor,patient,sources);else target.context();verifyGraph();
        }
        final void verifyGraph()throws SQLException {guard.verify();scope.requireDataSource(dataSource);}
        abstract void verify()throws SQLException;
    }
    private final class EvidenceProof extends WriteProof {
        final long event;final Map<String,Object> eventIdentity;
        final LinkedHashMap<Long,Anchor> inserted=new LinkedHashMap<>();
        final Map<Long,Map<String,Object>> nativeExpected=new LinkedHashMap<>(),bindingExpected=new LinkedHashMap<>();
        final Map<Long,String> boundTimes=new LinkedHashMap<>();
        boolean finished;
        EvidenceProof(Kind kind,long event,long patient,long actor,Collection<Long> sources,MedicalRecordValidityProjection.Scope scope)throws SQLException {
            super(kind,patient,actor,sources,scope,5);this.event=positive(event);eventIdentity=event();
        }
        Map<String,Object> event()throws SQLException {
            String sql=kind==Kind.CARE_PLAN_EVIDENCE?
                "SELECT e.id,e.patient_id,e.plan_id AS aggregate_id,e.action_id,e.revision_id,e.actor_id,e.event_type FROM care_plan_event e JOIN doctor_care_plan p ON p.id=e.plan_id AND p.patient_id=e.patient_id AND p.workflow_version=1 JOIN care_plan_action a ON a.id=e.action_id AND a.plan_id=p.id AND a.patient_id=p.patient_id AND a.revision_id=e.revision_id JOIN care_plan_revision r ON r.id=e.revision_id AND r.plan_id=p.id WHERE e.id=? LIMIT 2":
                "SELECT e.id,e.patient_id,e.alert_id AS aggregate_id,NULL AS action_id,NULL AS revision_id,e.actor_id,e.event_type FROM alert_event e JOIN alert_record a ON a.id=e.alert_id AND a.patient_id=e.patient_id AND a.workflow_version=1 WHERE e.id=? LIMIT 2";
            List<Map<String,Object>> rows=resource.query(sql,Collections.singletonList(event),2,7);if(rows.size()!=1)throw changed();Map<String,Object> row=rows.get(0);
            if(positive(row.get("id"))!=event||positive(row.get("patient_id"))!=patient||positive(row.get("actor_id"))!=actor)throw changed();positive(row.get("aggregate_id"));
            if(kind==Kind.CARE_PLAN_EVIDENCE){positive(row.get("action_id"));positive(row.get("revision_id"));if(!"RECEIPT_SUBMITTED".equals(row.get("event_type")))throw changed();}
            else if(row.get("action_id")!=null||row.get("revision_id")!=null||!Arrays.asList("SUBMIT","RESOLVE_REFERENCE_CHANGE").contains(row.get("event_type")))throw changed();
            return row;
        }
        long insert(long source,Long revision,Long version,String dependency) {
            try {
                guard.verify();if(finished||!sources.contains(source)||inserted.values().stream().anyMatch(a->a.record==source))throw changed();
                if(!eventIdentity.equals(event()))throw changed();authority(kind,actor,patient,sources);guard.arm();
                long id=checkedInsert(guard,kind==Kind.CARE_PLAN_EVIDENCE?
                    "INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(?,?,?)":
                    "INSERT INTO alert_evidence(event_id,source_type,source_id,source_revision,dependency_version,dependency_state) VALUES(?,?,?,?,?,?)",
                    kind==Kind.CARE_PLAN_EVIDENCE?new Object[]{event,"MEDICAL_RECORD",source}:new Object[]{event,"MEDICAL_RECORD",source,revision,version,dependency});
                Anchor anchor=new Anchor(id,patient,source,null);if(inserted.put(id,anchor)!=null)throw changed();
                Map<Long,Map<String,Object>> actual=nativeRows(resource,kind,Collections.singleton(id));Map<String,Object> row=actual.get(id);validateNative(kind,row);
                if(!matchesNative(row,anchor)||positive(row.get("event_id"))!=event||!Objects.equals(row.get("aggregate_id"),eventIdentity.get("aggregate_id"))||!Objects.equals(row.get("action_id"),eventIdentity.get("action_id")))throw changed();
                nativeExpected.put(id,row);MedicalRecordValidityProjection.State state=expectedSources.get(source);
                if(!storeRows(resource,kind,Collections.singleton(id),state,true).isEmpty()||!storeRows(resource,kind,Collections.singleton(id),state,false).isEmpty())throw changed();
                if(state.reviewParticipating||state.managed) {
                    String time=serverTime(resource);boundTimes.put(id,time);Timestamp stamp=Timestamp.from(LocalDateTime.parse(time,DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")).toInstant(java.time.ZoneOffset.UTC));
                    if(state.reviewParticipating)checkedUpdate(guard,"INSERT INTO medical_review_reference(reference_kind,reference_id,patient_id,record_id,source_generation,source_item_id,content_version,bound_at) VALUES(?,?,?,?,?,?,?,?)",kind.name(),id,patient,source,state.liveGeneration,null,state.version,stamp);
                    else checkedUpdate(guard,"INSERT INTO medical_record_reference_version(reference_kind,reference_id,patient_id,record_id,source_item_id,content_version,bound_at) VALUES(?,?,?,?,?,?,?)",kind.name(),id,patient,source,null,state.version,stamp);
                }
                guard.verify();return id;
            }catch(Throwable failure){throw guard.failure(failure);}
        }
        void complete(){try{if(finished||inserted.size()!=sources.size())throw changed();guard.complete(this);finished=true;}catch(Throwable failure){throw guard.failure(failure);}}
        @Override void verify()throws SQLException {
            guard.verify();if(inserted.size()!=sources.size()||!eventIdentity.equals(event()))throw changed();verifySources();
            Set<Long> ids=inserted.keySet();Map<Long,Map<String,Object>> actual=nativeRows(resource,kind,ids);
            MedicalRecordValidityProjection.State first=expectedSources.values().iterator().next();
            Map<Long,Map<String,Object>> old=storeRows(resource,kind,ids,first,true),current=storeRows(resource,kind,ids,first,false);
            for(Anchor anchor:inserted.values()) {
                if(!Objects.equals(nativeExpected.get(anchor.id),actual.get(anchor.id)))throw changed();MedicalRecordValidityProjection.State state=expectedSources.get(anchor.record);
                Map<String,Object> row=state.reviewParticipating?current.get(anchor.id):state.managed?old.get(anchor.id):null;
                if(state.reviewParticipating?old.containsKey(anchor.id):current.containsKey(anchor.id))throw changed();
                if(!state.reviewParticipating&&!state.managed){if(old.containsKey(anchor.id)||current.containsKey(anchor.id))throw changed();continue;}
                if(row==null)throw changed();validateBinding(kind,row,!state.reviewParticipating);
                if(!matchesBinding(row,anchor)||positive(row.get("content_version"))!=state.version||!Objects.equals(row.get("bound_at_text"),boundTimes.get(anchor.id))||state.reviewParticipating&&!Objects.equals(row.get("source_generation"),state.liveGeneration))throw changed();
                if(bindingExpected.containsKey(anchor.id)){if(!bindingExpected.get(anchor.id).equals(row))throw changed();}else bindingExpected.put(anchor.id,row);
            }
            verifySources();guard.verify();
        }
    }
    private Map<Long,Map<String,Object>> nativeRows(Resource resource,Kind kind,Set<Long> ids)throws SQLException {
        return indexed(resource.query(readAnchorSql(kind)+" WHERE v.id IN ("+marks(ids.size())+") ORDER BY v.id LIMIT "+(ids.size()+1),new ArrayList<>(ids),ids.size()+1,7),ids,"id");
    }
    private Map<Long,Map<String,Object>> storeRows(Resource resource,Kind kind,Set<Long> ids,MedicalRecordValidityProjection.State state,boolean old)throws SQLException {
        if(old?state.parent9Storage!=MedicalRecordCorrectionSchema.Status.READY:state.reviewStorage!=MedicalReviewSchema.Status.READY)return Collections.emptyMap();
        return bindings(resource,kind,ids,old);
    }
    private String serverTime(Resource resource)throws SQLException {
        List<Map<String,Object>> rows=resource.query("SELECT DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%f') AS bound_at_text",Collections.emptyList(),1,1);
        if(rows.size()!=1)throw malformed();Map<String,Object> row=new LinkedHashMap<>(rows.get(0));row.put("bound_at_null",0L);timestamp(row,"bound_at");return(String)row.get("bound_at_text");
    }
    private long checkedInsert(MedicalRecordReferenceWriteGuard guard,String sql,Object...args)throws SQLException {
        guard.arm();long id;
        try(PreparedStatement statement=guard.connection().prepareStatement(sql,new String[]{"id"})) {
            statement.setQueryTimeout(guard.timeout());for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
            if(statement.executeUpdate()!=1)throw changed();guard.verify();
            try(ResultSet keys=statement.getGeneratedKeys()) {
                if(keys==null||keys.getMetaData().getColumnCount()!=1||!keys.next())throw changed();
                id=positive(keys.getObject(1));if(keys.next())throw changed();
            }
        }
        guard.verify();return id;
    }
    private void checkedUpdate(MedicalRecordReferenceWriteGuard guard,String sql,Object...args)throws SQLException {
        guard.arm();
        try(PreparedStatement statement=guard.connection().prepareStatement(sql)) {
            statement.setQueryTimeout(guard.timeout());
            for(int i=0;i<args.length;i++){if(args[i] instanceof Timestamp)statement.setTimestamp(i+1,(Timestamp)args[i],Calendar.getInstance(TimeZone.getTimeZone("UTC")));else statement.setObject(i+1,args[i]);}
            if(statement.executeUpdate()!=1)throw changed();
        }
        guard.verify();
    }
    /** Closed automatic threshold adapter. It never grants human source-read authority. */
    ThresholdFrame prepareThreshold(long patient,long owner,long source,long item,long rule,MedicalRecordValidityProjection.Scope scope) {
        try {
            ThresholdProof proof=new ThresholdProof(new ThresholdTarget(patient,owner,source,item,rule),scope);
            if(!proof.admitted){proof.guard.close();return null;}
            return new ThresholdFrame(proof);
        }catch(SQLException|org.springframework.dao.DataAccessException failure){throw MedicalReviewSchema.notReady();}
    }
    final class ThresholdFrame implements AutoCloseable {
        private final ThresholdProof proof;
        private ThresholdFrame(ThresholdProof proof){this.proof=proof;}
        MedicalRecordValidityProjection.State source(){return proof.expectedSources.get(proof.target.source);}
        void expectTarget(org.familyhealthcare.entity.MedicalRecordItem item,org.familyhealthcare.entity.AlertRule rule,org.familyhealthcare.entity.HealthIndicator indicator) {
            try{proof.expectTarget(item,rule,indicator);}catch(Throwable failure){throw proof.guard.failure(failure);}
        }
        boolean refreshExisting(org.familyhealthcare.entity.AlertRecord alert,String value,LocalDateTime time) {
            try{return proof.refresh(alert,value,time);}catch(Throwable failure){throw proof.guard.failure(failure);}
        }
        ThresholdInsert insertNew(String level,String title,String value,LocalDateTime time) {
            try{return proof.insert(level,title,value,time);}catch(Throwable failure){throw proof.guard.failure(failure);}
        }
        void complete(){try{if(proof.finished||proof.alertExpected==null)throw changed();proof.guard.complete(proof);proof.finished=true;}catch(Throwable failure){throw proof.guard.failure(failure);}}
        RuntimeException failure(Throwable failure){return proof.guard.failure(failure);}
        @Override public void close(){proof.guard.close();}
    }
    /** Process-local facts from this finite native creation, not a transferable permission. */
    static final class ThresholdInsert {
        final long id;private final ThresholdTarget target;private final Map<String,Object> binding,oldBinding,created;
        private ThresholdInsert(long id,ThresholdTarget target,Map<String,Object> oldBinding,Map<String,Object> binding,Map<String,Object> created){this.id=id;this.target=target;this.oldBinding=oldBinding;this.binding=binding;this.created=created;}
    }
    private static final class ThresholdTarget {
        final long patient,owner,source,item,rule;
        final org.springframework.web.context.request.RequestAttributes attributes;
        final javax.servlet.http.HttpServletRequest request;final Long human;
        private Map<String,Object> witness;
        ThresholdTarget(long patient,long owner,long source,long item,long rule) {
            this.patient=positive(patient);this.owner=positive(owner);this.source=positive(source);this.item=positive(item);this.rule=positive(rule);
            attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if(attributes!=null&&!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes))throw changed();
            request=attributes==null?null:((org.springframework.web.context.request.ServletRequestAttributes)attributes).getRequest();
            human=org.familyhealthcare.util.CurrentUserUtil.getCurrentUserId();
            if(request==null?human!=null:human==null||positive(human)!=owner)throw changed();
        }
        void context() {
            if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()!=attributes
                    ||request!=null&&((org.springframework.web.context.request.ServletRequestAttributes)attributes).getRequest()!=request
                    ||!Objects.equals(human,org.familyhealthcare.util.CurrentUserUtil.getCurrentUserId()))throw changed();
        }
        boolean admit(Resource resource)throws SQLException {
            context();if(request!=null)return true;
            List<Map<String,Object>> rows=resource.query("SELECT id,patient_id,user_id,enabled FROM alert_rule WHERE patient_id=? AND user_id=? AND enabled=1 ORDER BY id LIMIT 1",Arrays.asList(patient,owner),1,4);
            if(rows.isEmpty())return false;witness=rows.get(0);positive(witness.get("id"));
            if(positive(witness.get("patient_id"))!=patient||positive(witness.get("user_id"))!=owner||nonnegative(witness.get("enabled"))!=1)throw changed();return true;
        }
        void verify(Resource resource)throws SQLException {
            context();if(request==null) {
                if(witness==null)throw changed();List<Map<String,Object>> rows=resource.query("SELECT id,patient_id,user_id,enabled FROM alert_rule WHERE id=? LIMIT 2",Collections.singletonList(witness.get("id")),2,4);
                if(rows.size()!=1||!witness.equals(rows.get(0)))throw changed();
            }
        }
    }
    private static final String THRESHOLD_ALERT_COLUMNS="id,rule_id,patient_id,user_id,alert_type,alert_level,alert_title,triggered_value,triggered_at,last_triggered_at,source_type,source_id,dedupe_key,occurrence_count,status,acknowledged_by,acknowledged_at,resolved_by,resolved_at,created_at,updated_at";
    private static final String THRESHOLD_LEGACY="(alert_type IS NULL OR alert_type<>'MEASUREMENT_FOLLOW_UP')";
    private static final String THRESHOLD_ACTIVE="('PENDING','CONFIRMED','OBSERVING','CONSULTED','RECHECKED')";
    private static String thresholdDedupe(ThresholdTarget t){return "RULE:"+t.rule+":ITEM:"+t.item;}
    private static Map<String,Object> scalarDates(Map<String,Object> row) {
        if(row==null)return null;Map<String,Object> result=new LinkedHashMap<>(row);
        for(Map.Entry<String,Object> entry:result.entrySet())if(entry.getValue() instanceof Timestamp)entry.setValue(((Timestamp)entry.getValue()).toLocalDateTime());
        return Collections.unmodifiableMap(result);
    }
    private Map<String,Object> thresholdAlert(Resource resource,long id)throws SQLException {
        List<Map<String,Object>> rows=resource.query("SELECT "+THRESHOLD_ALERT_COLUMNS+" FROM alert_record WHERE id=? LIMIT 2",Collections.singletonList(id),2,21);
        if(rows.size()>1)throw malformed();return rows.isEmpty()?null:scalarDates(rows.get(0));
    }
    private Map<String,Object> thresholdDedupe(Resource resource,ThresholdTarget target)throws SQLException {
        List<Map<String,Object>> rows=resource.query("SELECT "+THRESHOLD_ALERT_COLUMNS+" FROM alert_record WHERE patient_id=? AND dedupe_key=? AND "+THRESHOLD_LEGACY+" AND status IN "+THRESHOLD_ACTIVE+" ORDER BY id DESC LIMIT 1",Arrays.asList(target.patient,thresholdDedupe(target)),1,21);
        return rows.isEmpty()?null:scalarDates(rows.get(0));
    }
    private Map<String,Object> thresholdItem(Resource resource,ThresholdTarget target)throws SQLException {
        List<Map<String,Object>> rows=resource.query("SELECT id,record_id,item_code,item_name,result_value,unit FROM medical_record_item WHERE id=? AND record_id=? LIMIT 2",Arrays.asList(target.item,target.source),2,6);
        if(rows.size()>1)throw malformed();return rows.isEmpty()?null:rows.get(0);
    }
    private Map<String,Object> thresholdRule(Resource resource,ThresholdTarget target)throws SQLException {
        List<Map<String,Object>> rows=resource.query("SELECT id,patient_id,user_id,indicator_code,indicator_name,threshold_type,threshold_value,alert_level,enabled FROM alert_rule WHERE id=? AND patient_id=? LIMIT 2",Arrays.asList(target.rule,target.patient),2,9);
        if(rows.size()>1)throw malformed();return rows.isEmpty()?null:rows.get(0);
    }
    private List<Map<String,Object>> thresholdIndicators(Resource resource,String code)throws SQLException {
        return resource.query("SELECT id,item_code,item_name,aliases,unit FROM health_indicator WHERE item_code=? ORDER BY id LIMIT 2",Collections.singletonList(code),2,5);
    }
    private static Map<String,Object> itemFacts(org.familyhealthcare.entity.MedicalRecordItem item){return WorkflowData.map("id",item.getId(),"record_id",item.getRecordId(),"item_code",item.getItemCode(),"item_name",item.getItemName(),"result_value",item.getResultValue(),"unit",item.getUnit());}
    private static Map<String,Object> ruleFacts(org.familyhealthcare.entity.AlertRule rule){return WorkflowData.map("id",rule.getId(),"patient_id",rule.getPatientId(),"user_id",rule.getUserId(),"indicator_code",rule.getIndicatorCode(),"indicator_name",rule.getIndicatorName(),"threshold_type",rule.getThresholdType(),"threshold_value",rule.getThresholdValue(),"alert_level",rule.getAlertLevel(),"enabled",rule.getEnabled());}
    private static Map<String,Object> indicatorFacts(org.familyhealthcare.entity.HealthIndicator indicator){return WorkflowData.map("id",indicator.getId(),"item_code",indicator.getItemCode(),"item_name",indicator.getItemName(),"aliases",indicator.getAliases(),"unit",indicator.getUnit());}
    private static Map<String,Object> alertFacts(org.familyhealthcare.entity.AlertRecord alert){return WorkflowData.map("id",alert.getId(),"rule_id",alert.getRuleId(),"patient_id",alert.getPatientId(),"user_id",alert.getUserId(),"alert_type",alert.getAlertType(),"alert_level",alert.getAlertLevel(),"alert_title",alert.getAlertTitle(),"triggered_value",alert.getTriggeredValue(),"triggered_at",alert.getTriggeredAt(),"last_triggered_at",alert.getLastTriggeredAt(),"source_type",alert.getSourceType(),"source_id",alert.getSourceId(),"dedupe_key",alert.getDedupeKey(),"occurrence_count",alert.getOccurrenceCount(),"status",alert.getStatus(),"acknowledged_by",alert.getAcknowledgedBy(),"acknowledged_at",alert.getAcknowledgedAt(),"resolved_by",alert.getResolvedBy(),"resolved_at",alert.getResolvedAt(),"created_at",alert.getCreatedAt(),"updated_at",alert.getUpdatedAt());}
    private static Map<String,Object> selectedIndicator(List<Map<String,Object>> rows,String code){return rows.size()==1&&Objects.equals(code,rows.get(0).get("item_code"))?rows.get(0):null;}
    private final class ThresholdReference {
        final Map<String,Object> nativeRow,old,current,closure;final ReferenceSnapshot snapshot;
        ThresholdReference(Resource resource,Anchor anchor,MedicalRecordValidityProjection.State state)throws SQLException {
            Set<Long> ids=Collections.singleton(anchor.id);nativeRow=nativeRows(resource,Kind.LEGACY_ALERT,ids).get(anchor.id);
            if(nativeRow!=null)validateNative(Kind.LEGACY_ALERT,nativeRow);
            old=storeRows(resource,Kind.LEGACY_ALERT,ids,state,true).get(anchor.id);current=storeRows(resource,Kind.LEGACY_ALERT,ids,state,false).get(anchor.id);
            Map<String,Object> selectedClosure=null;
            if(current!=null&&old==null&&matchesNative(nativeRow,anchor)&&available(state,anchor)) {
                validateBinding(Kind.LEGACY_ALERT,current,false);
                if(matchesBinding(current,anchor)&&!Objects.equals(current.get("source_generation"),state.liveGeneration)) {
                    GenerationKey key=new GenerationKey(anchor.record,(String)current.get("source_generation"));selectedClosure=closures(resource,Collections.singletonList(key)).get(key);
                }
            }
            closure=selectedClosure;snapshot=compose(Kind.LEGACY_ALERT,anchor,nativeRow,old,current,state,closure,"",MedicalReviewQualificationReader.Semantics.CURRENT_CHECK,Instant.now());
        }
    }
    private final class ThresholdProof extends WriteProof {
        final boolean admitted;private Map<String,Object> itemExpected,ruleExpected,alertExpected;
        private List<Map<String,Object>> indicatorExpected;private ThresholdReference referenceExpected;
        private long alert;private boolean finished;
        ThresholdProof(ThresholdTarget target,MedicalRecordValidityProjection.Scope scope)throws SQLException {
            super(target,scope);MedicalRecordValidityProjection.State state=expectedSources.get(target.source);
            admitted=state!=null&&state.eligible()&&target.admit(resource);
        }
        void expectTarget(org.familyhealthcare.entity.MedicalRecordItem item,org.familyhealthcare.entity.AlertRule rule,org.familyhealthcare.entity.HealthIndicator indicator)throws SQLException {
            guard.verify();if(!admitted||itemExpected!=null||alertExpected!=null)throw changed();target.verify(resource);
            Map<String,Object> itemRow=thresholdItem(resource,target),ruleRow=thresholdRule(resource,target);
            if(!itemFacts(item).equals(itemRow)||!ruleFacts(rule).equals(ruleRow))throw changed();
            List<Map<String,Object>> found=thresholdIndicators(resource,rule.getIndicatorCode());
            if(!Objects.equals(indicator==null?null:indicatorFacts(indicator),selectedIndicator(found,rule.getIndicatorCode())))throw changed();
            itemExpected=itemRow;ruleExpected=ruleRow;indicatorExpected=found;verifyGraph();
        }
        void verifyTarget()throws SQLException {
            target.context();if(itemExpected==null||!itemExpected.equals(thresholdItem(resource,target))||!ruleExpected.equals(thresholdRule(resource,target))||!indicatorExpected.equals(thresholdIndicators(resource,(String)ruleExpected.get("indicator_code"))))throw changed();
        }
        boolean refresh(org.familyhealthcare.entity.AlertRecord selected,String value,LocalDateTime time)throws SQLException {
            guard.verify();if(alertExpected!=null||finished||itemExpected==null)throw changed();target.verify(resource);
            if(time==null||time.getNano()!=0||!Objects.equals(value,thresholdDisplay(itemExpected)))throw changed();
            Map<String,Object> actual=thresholdDedupe(resource,target);if(!alertFacts(selected).equals(actual))throw changed();
            alert=positive(selected.getId());Anchor anchor=new Anchor(alert,patient,target.source,target.item);
            if(!Objects.equals(actual.get("rule_id"),target.rule)||!Objects.equals(actual.get("source_id"),target.item)||!"MEDICAL_RECORD_ITEM".equals(actual.get("source_type")))return false;
            MedicalRecordValidityProjection.State state=expectedSources.get(target.source);ThresholdReference reference=new ThresholdReference(resource,anchor,state);
            boolean protectedAlert=state.managed||state.reviewParticipating||reference.old!=null||reference.current!=null;
            if(!(protectedAlert?"CURRENT":"UNTRACKED").equals(reference.snapshot.status))return false;
            Map<String,Object> expected=new LinkedHashMap<>(actual);expected.put("last_triggered_at",time);expected.put("occurrence_count",(selected.getOccurrenceCount()==null?1:selected.getOccurrenceCount())+1);
            List<Object> args=new ArrayList<>(Arrays.asList(time,expected.get("occurrence_count")));String set="last_triggered_at=?,occurrence_count=?";
            if(!protectedAlert){set+=",triggered_value=?";args.add(value);expected.put("triggered_value",value);}
            boolean preservedUpdated=actual.get("updated_at")!=null;if(preservedUpdated){set+=",updated_at=?";args.add(actual.get("updated_at"));}
            args.addAll(Arrays.asList(alert,patient,target.rule,target.item,thresholdDedupe(target)));
            checkedUpdate(guard,"UPDATE alert_record SET "+set+" WHERE id=? AND patient_id=? AND rule_id=? AND source_type='MEDICAL_RECORD_ITEM' AND source_id=? AND dedupe_key=? AND "+THRESHOLD_LEGACY,args.toArray());
            Map<String,Object> persisted=thresholdAlert(resource,alert);if(persisted==null)throw changed();
            if(!preservedUpdated)expected.put("updated_at",persisted.get("updated_at"));
            if(!expected.equals(persisted))throw changed();alertExpected=Collections.unmodifiableMap(expected);referenceExpected=reference;return true;
        }
        ThresholdInsert insert(String level,String title,String value,LocalDateTime time)throws SQLException {
            guard.verify();if(alertExpected!=null||finished||itemExpected==null)throw changed();target.verify(resource);
            if(!Objects.equals(level,ruleExpected.get("alert_level"))||!Objects.equals(title,MedicalRecordThresholdWriter.title((String)ruleExpected.get("indicator_name")))||!Objects.equals(value,thresholdDisplay(itemExpected))||time==null||time.getNano()!=0)throw changed();
            if(thresholdDedupe(resource,target)!=null)throw changed();
            alert=checkedInsert(guard,"INSERT INTO alert_record(rule_id,patient_id,user_id,alert_type,alert_level,alert_title,triggered_value,triggered_at,last_triggered_at,source_type,source_id,dedupe_key,occurrence_count,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",target.rule,patient,target.owner,"INDICATOR",level,title,value,time,time,"MEDICAL_RECORD_ITEM",target.item,thresholdDedupe(target),1,"PENDING");
            Map<String,Object> expected=WorkflowData.map("id",alert,"rule_id",target.rule,"patient_id",patient,"user_id",target.owner,"alert_type","INDICATOR","alert_level",level,"alert_title",title,"triggered_value",value,"triggered_at",time,"last_triggered_at",time,"source_type","MEDICAL_RECORD_ITEM","source_id",target.item,"dedupe_key",thresholdDedupe(target),"occurrence_count",1,"status","PENDING","acknowledged_by",null,"acknowledged_at",null,"resolved_by",null,"resolved_at",null);
            Map<String,Object> persisted=thresholdAlert(resource,alert);if(persisted==null)throw changed();
            // Only these omitted native defaults are DB-owned; authored trigger times remain exact expectations.
            expected.put("created_at",persisted.get("created_at"));expected.put("updated_at",persisted.get("updated_at"));if(!expected.equals(persisted))throw changed();
            MedicalRecordValidityProjection.State state=expectedSources.get(target.source);Anchor anchor=new Anchor(alert,patient,target.source,target.item);
            ThresholdReference before=new ThresholdReference(resource,anchor,state);if(!matchesNative(before.nativeRow,anchor)||before.old!=null||before.current!=null)throw changed();
            String timeText=null;
            if(state.reviewParticipating||state.managed) {
                timeText=serverTime(resource);Timestamp stamp=Timestamp.from(LocalDateTime.parse(timeText,DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")).toInstant(java.time.ZoneOffset.UTC));
                if(state.reviewParticipating)checkedUpdate(guard,"INSERT INTO medical_review_reference(reference_kind,reference_id,patient_id,record_id,source_generation,source_item_id,content_version,bound_at) VALUES(?,?,?,?,?,?,?,?)",kind.name(),alert,patient,target.source,state.liveGeneration,target.item,state.version,stamp);
                else checkedUpdate(guard,"INSERT INTO medical_record_reference_version(reference_kind,reference_id,patient_id,record_id,source_item_id,content_version,bound_at) VALUES(?,?,?,?,?,?,?)",kind.name(),alert,patient,target.source,target.item,state.version,stamp);
            }
            ThresholdReference after=new ThresholdReference(resource,anchor,state);Map<String,Object> binding=state.reviewParticipating?after.current:state.managed?after.old:null;
            if(state.reviewParticipating?after.old!=null:after.current!=null)throw changed();
            if(state.reviewParticipating||state.managed) {
                if(binding==null)throw changed();validateBinding(kind,binding,!state.reviewParticipating);
                if(!matchesBinding(binding,anchor)||positive(binding.get("content_version"))!=state.version||!Objects.equals(timeText,binding.get("bound_at_text"))||state.reviewParticipating&&!Objects.equals(state.liveGeneration,binding.get("source_generation")))throw changed();
            }else if(after.old!=null||after.current!=null)throw changed();
            if(!fresh(after.snapshot.status)||!Objects.equals(before.nativeRow,after.nativeRow))throw changed();
            alertExpected=Collections.unmodifiableMap(new LinkedHashMap<>(expected));referenceExpected=after;
            return new ThresholdInsert(alert,target,after.old,after.current,alertExpected);
        }
        @Override void verify()throws SQLException {
            guard.verify();if(alertExpected==null||referenceExpected==null)throw changed();verifySources();verifyTarget();
            if(!alertExpected.equals(thresholdAlert(resource,alert)))throw changed();
            ThresholdReference actual=new ThresholdReference(resource,new Anchor(alert,patient,target.source,target.item),expectedSources.get(target.source));
            if(!Objects.equals(referenceExpected.nativeRow,actual.nativeRow)||!Objects.equals(referenceExpected.old,actual.old)||!Objects.equals(referenceExpected.current,actual.current)||!Objects.equals(referenceExpected.closure,actual.closure)||!fresh(actual.snapshot.status))throw changed();
            verifySources();target.verify(resource);verifyGraph();
        }
    }
    private static String thresholdDisplay(Map<String,Object> item){return item.get("result_value")+(item.get("unit")!=null?" "+item.get("unit"):"");}

    /** A short, bounded fresh RC observation, completed before any audience/provider work. */
    boolean thresholdCurrent(MedicalRecordThresholdWriter.Candidate candidate) {
        if(candidate==null)return false;thresholdOutside(candidate);
        TransactionTemplate owner=new TransactionTemplate(transactions);owner.setReadOnly(true);owner.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return Boolean.TRUE.equals(owner.execute(status->{
            try {
                Resource resource=new Resource();MedicalRecordValidityProjection.Scope scope=validity.open();scope.requireDataSource(dataSource);
                if(resource.isolation!=Connection.TRANSACTION_READ_COMMITTED||!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw MedicalReviewSchema.notReady();
                Map<String,Object> previous=null;MedicalRecordValidityProjection.State last=null;
                for(int pass=0;pass<3;pass++) {
                    ThresholdPass observed=thresholdPass(resource,scope,candidate);resource.verify();scope.requireDataSource(dataSource);
                    if(observed==null)return false;
                    if(previous!=null&&previous.equals(observed.signature)) {
                        last=thresholdSource(scope,candidate.record);resource.verify();
                        if(last==null||!last.eligible()||!Objects.equals(last.sourceReleaseSignature,observed.source.sourceReleaseSignature))return false;
                        candidate.insert.target.verify(resource);resource.verify();scope.requireDataSource(dataSource);
                        if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw MedicalReviewSchema.notReady();return true;
                    }
                    previous=observed.signature;
                }
                throw new WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","Current reference qualification is unavailable. Reload and try again.");
            }catch(SQLException|org.springframework.dao.DataAccessException failure){throw MedicalReviewSchema.notReady();}
        }));
    }
    void thresholdOutside(MedicalRecordThresholdWriter.Candidate candidate) {
        graph();if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.hasResource(dataSource))throw MedicalReviewSchema.notReady();candidate.insert.target.context();
    }
    private static final class ThresholdPass {
        final MedicalRecordValidityProjection.State source;final Map<String,Object> signature;
        ThresholdPass(MedicalRecordValidityProjection.State source,Map<String,Object> signature){this.source=source;this.signature=signature;}
    }
    private MedicalRecordValidityProjection.State thresholdSource(MedicalRecordValidityProjection.Scope scope,long source) {
        scope.requireDataSource(dataSource);MedicalRecordValidityProjection.State state=scope.qualifiedSnapshot(Collections.singleton(source)).get(source);
        if(state==null)return null;if(state.id!=source||!state.reviewObserved||state.sourceReleaseSignature==null)throw malformed();return state;
    }
    private ThresholdPass thresholdPass(Resource resource,MedicalRecordValidityProjection.Scope scope,MedicalRecordThresholdWriter.Candidate candidate)throws SQLException {
        ThresholdInsert inserted=candidate.insert;ThresholdTarget target=inserted.target;target.verify(resource);
        MedicalRecordValidityProjection.State state=thresholdSource(scope,candidate.record);resource.verify();
        if(state==null)return null;
        Map<String,Object> item=thresholdItem(resource,target),rule=thresholdRule(resource,target),alert=thresholdAlert(resource,candidate.alert);
        // Read both stores and the presented binding's exact closed generation even without live channels.
        ThresholdReference reference=new ThresholdReference(resource,new Anchor(candidate.alert,candidate.patient,candidate.record,candidate.item),state);
        if(!Objects.equals(state.patientId,candidate.patient)||!Objects.equals(state.ownerId,candidate.sourceOwner)||!Objects.equals(state.version,candidate.version)||state.managed!=candidate.managed||!state.eligible()||!fresh(reference.snapshot.status)||!Objects.equals(inserted.oldBinding,reference.old)||!Objects.equals(inserted.binding,reference.current))return null;
        if(item==null||!Objects.equals(item.get("id"),candidate.item)||!Objects.equals(item.get("record_id"),candidate.record)||!Objects.equals(item.get("result_value"),candidate.value)||!Objects.equals(item.get("unit"),candidate.unit)
                ||alert==null||!Objects.equals(alert.get("patient_id"),candidate.patient)||!Objects.equals(alert.get("user_id"),candidate.owner)||!Objects.equals(alert.get("source_id"),candidate.item)||!"MEDICAL_RECORD_ITEM".equals(alert.get("source_type"))||!Objects.equals(alert.get("rule_id"),candidate.rule)
                ||!Arrays.asList("PENDING","CONFIRMED","OBSERVING","CONSULTED","RECHECKED").contains(alert.get("status"))||rule==null||!Objects.equals(rule.get("patient_id"),candidate.patient)||!Integer.valueOf(1).equals(rule.get("enabled"))||!Arrays.asList("WARNING","CRITICAL").contains(rule.get("alert_level"))||rule.get("indicator_code")==null)return null;
        for(String immutable:Arrays.asList("alert_type","alert_level","alert_title","triggered_value","triggered_at","dedupe_key"))if(!Objects.equals(inserted.created.get(immutable),alert.get(immutable)))return null;
        List<Map<String,Object>> found=thresholdIndicators(resource,(String)rule.get("indicator_code"));Map<String,Object> selected=selectedIndicator(found,(String)rule.get("indicator_code"));
        org.familyhealthcare.entity.MedicalRecordItem nativeItem=new org.familyhealthcare.entity.MedicalRecordItem();nativeItem.setItemCode((String)item.get("item_code"));nativeItem.setItemName((String)item.get("item_name"));nativeItem.setResultValue((String)item.get("result_value"));nativeItem.setUnit((String)item.get("unit"));
        org.familyhealthcare.entity.AlertRule nativeRule=new org.familyhealthcare.entity.AlertRule();nativeRule.setIndicatorCode((String)rule.get("indicator_code"));nativeRule.setThresholdType((String)rule.get("threshold_type"));nativeRule.setThresholdValue((String)rule.get("threshold_value"));
        org.familyhealthcare.entity.HealthIndicator indicator=null;if(selected!=null){indicator=new org.familyhealthcare.entity.HealthIndicator();indicator.setItemCode((String)selected.get("item_code"));indicator.setItemName((String)selected.get("item_name"));indicator.setAliases((String)selected.get("aliases"));indicator.setUnit((String)selected.get("unit"));}
        BigDecimal number=candidate.managed?MedicalRecordValidityProjection.exactNumeric(nativeItem.getResultValue()):MedicalRecordThresholdWriter.legacyNumeric(nativeItem.getResultValue());
        if(!MedicalRecordThresholdWriter.matches(nativeItem,nativeRule,indicator)||number==null||!MedicalRecordThresholdWriter.breached(number,nativeRule.getThresholdType(),nativeRule.getThresholdValue())||candidate.managed&&(indicator==null||!MedicalRecordValidityProjection.exactUnit(nativeItem.getUnit(),indicator.getUnit())))return null;
        target.context();resource.verify();scope.requireDataSource(dataSource);
        return new ThresholdPass(state,Collections.unmodifiableMap(WorkflowData.map("source",state.sourceReleaseSignature,"item",item,"rule",rule,"indicator",found,"alert",alert,"anchor",reference.nativeRow,"old",reference.old,"review",reference.current,"closure",reference.closure,"witness",target.witness)));
    }

    public MedicalRecordValidityProjection.State requireEligible(MedicalRecordValidityProjection.Scope scope,long patient,long source){MedicalRecordValidityProjection.State state=scope.one(source);if(state==null||!Objects.equals(state.patientId,patient)||!state.eligible())throw changed();return state;}
    public void requireCurrent(Kind kind,List<Anchor> anchors,MedicalRecordValidityProjection.Scope scope){
        if(anchors==null||anchors.size()>100)throw limit();
        if(anchors.isEmpty()||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            for(String status:statuses(kind,anchors,scope).values())if(!fresh(status))throw changed();return;
        }
        CurrentProof proof;
        try{proof=new CurrentProof(kind,anchors,scope);}
        catch(SQLException|org.springframework.dao.DataAccessException failure){throw MedicalReviewSchema.notReady();}
        try{proof.guard.arm();proof.guard.complete(proof);}catch(Throwable failure){throw proof.guard.failure(failure);}
    }
    private final class CurrentProof extends WriteProof {
        final LinkedHashMap<Long,Anchor> anchors;final Map<Long,Map<String,Object>> nativeExpected,oldExpected,newExpected;
        CurrentProof(Kind kind,List<Anchor> requested,MedicalRecordValidityProjection.Scope scope)throws SQLException {
            super(kind,currentPatient(requested),currentActor(),currentSources(requested),scope,50);
            anchors=MedicalRecordReferenceVersions.this.anchors(Collections.singletonMap(kind,requested)).get(kind);
            nativeExpected=nativeRows(resource,kind,anchors.keySet());MedicalRecordValidityProjection.State first=expectedSources.values().iterator().next();
            oldExpected=storeRows(resource,kind,anchors.keySet(),first,true);newExpected=storeRows(resource,kind,anchors.keySet(),first,false);verify();
        }
        @Override void verify()throws SQLException {
            verifySources();Map<Long,Map<String,Object>> actual=nativeRows(resource,kind,anchors.keySet());MedicalRecordValidityProjection.State first=expectedSources.values().iterator().next();
            Map<Long,Map<String,Object>> old=storeRows(resource,kind,anchors.keySet(),first,true),current=storeRows(resource,kind,anchors.keySet(),first,false);
            if(!nativeExpected.equals(actual)||!oldExpected.equals(old)||!newExpected.equals(current))throw changed();
            for(Anchor anchor:anchors.values()) {
                Map<String,Object> row=actual.get(anchor.id);if(row==null)throw changed();validateNative(kind,row);
                ReferenceSnapshot snapshot=compose(kind,anchor,row,old.get(anchor.id),current.get(anchor.id),expectedSources.get(anchor.record),null,"",MedicalReviewQualificationReader.Semantics.CURRENT_CHECK,Instant.now());
                if(!fresh(snapshot.status))throw changed();
            }
            verifySources();guard.verify();
        }
    }
    private static long currentPatient(List<Anchor> anchors){if(anchors==null||anchors.isEmpty()||anchors.size()>50)throw limit();long patient=positive(anchors.get(0).patient);for(Anchor anchor:anchors)if(anchor==null||anchor.patient!=patient)throw changed();return patient;}
    private static Collection<Long> currentSources(List<Anchor> anchors){SortedSet<Long> sources=new TreeSet<>();for(Anchor anchor:anchors){if(anchor==null)throw changed();sources.add(positive(anchor.record));}return sources;}

    public static boolean fresh(String status){return "CURRENT".equals(status)||"UNTRACKED".equals(status);}
    public static WorkflowException changed(){return new WorkflowException(409,"REFERENCE_CHANGED","使用证据前请重新核对当前来源。");}
    public static WorkflowException limit(){return new WorkflowException(409,"REFERENCE_CHECK_LIMIT","证据引用过多，请人工核对。");}
    private long insert(String sql,Object...args){GeneratedKeyHolder key=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);return key.getKey().longValue();}
}
