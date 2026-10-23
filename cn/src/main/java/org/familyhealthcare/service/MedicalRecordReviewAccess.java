package org.familyhealthcare.service;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State;

/** 当前授权单独开启新事务，不能使用详情的旧内容快照。 */
@Service
public class MedicalRecordReviewAccess {
    private final JdbcTemplate jdbc;
    /** Identity only: never acquire or bind another datasource. */
    public void requireDataSource(javax.sql.DataSource expected) {
        if (expected == null || jdbc.getDataSource() != expected)
            throw new IllegalStateException("The configured datasource does not match.");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.familyhealthcare.service.careplan.CarePlanAuthorizationService carePlanAuthorization;

    public MedicalRecordReviewAccess(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
    public void requireCurrent(MedicalRecord snapshot, boolean write, boolean doctorWorkspace) {
        requireAuthority(snapshot, write, doctorWorkspace);
    }

    /** 写入口已持有 authority 父锁；不得再开启独立事务等待自己持有的锁。 */
    public void requireLockedCurrent(MedicalRecord snapshot, long actor, boolean write, boolean doctorWorkspace) {
        org.familyhealthcare.service.workflow.WorkflowData.mutationTransaction(jdbc);
        if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) denied();
        requireAuthority(snapshot, write, doctorWorkspace);
    }

    private void requireAuthority(MedicalRecord snapshot, boolean write, boolean doctorWorkspace) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,patient_id,user_id FROM medical_record WHERE id=?",snapshot.getId());
        if(rows.size()!=1 || !Objects.equals(number(rows.get(0).get("id")),snapshot.getId())
                || !Objects.equals(number(rows.get(0).get("patient_id")),snapshot.getPatientId())
                || !Objects.equals(number(rows.get(0).get("user_id")),snapshot.getUserId())) denied();
        requireParents(snapshot,write,doctorWorkspace);
    }

    private void requireParents(MedicalRecord snapshot, boolean write, boolean doctorWorkspace) {
        Long actor=CurrentUserUtil.getCurrentUserId();
        if(actor==null || count("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",actor)==0
                || count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.status=1 AND COALESCE(r.deleted,0)=0",actor)==0) denied();
        boolean admin=role(actor,"admin"), doctor=role(actor,"doctor");
        Long patient=snapshot.getPatientId();
        if(patient==null) {if(doctorWorkspace || (!admin && !Objects.equals(actor,snapshot.getUserId()))) denied();return;}
        List<Long> owners=jdbc.queryForList("SELECT user_id FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",Long.class,patient);
        if(owners.isEmpty()) denied();
        boolean assigned=doctor && count("SELECT COUNT(*) FROM doctor_patient_assignment WHERE doctor_user_id=? AND patient_id=? AND status='ACTIVE'",actor,patient)>0;
        // 管理员身份不能代替当前有效指派的医生作出独立临床复核。
        if(doctorWorkspace) {if(!assigned) denied();return;}
        if(admin || assigned || Objects.equals(owners.get(0),actor)) return;
        // Explicit grants replace legacy family membership, including revoked or expired grants.
        List<Map<String,Object>> grants=jdbc.queryForList("SELECT access_level,visible_modules,status,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired FROM care_access_grant WHERE grantee_user_id=? AND patient_id=?",actor,patient);
        if(!grants.isEmpty()) {
            for(Map<String,Object> grant:grants) if("ACTIVE".equals(grant.get("status")) && truth(grant.get("unexpired")) && permits(grant,write)) return;
            denied();
        }
        for(Map<String,Object> member:jdbc.queryForList("SELECT access_level,visible_modules FROM care_member WHERE user_id=? AND patient_id=?",actor,patient)) if(permits(member,write)) return;
        denied();
    }
    /** Post-delete WRITE verifier only; its caller owns the original binding and still-held locks. */
    public void requireLockedDeletedSource(MedicalRecord savedBinding, long actor) {
        org.familyhealthcare.service.workflow.WorkflowData.mutationTransaction(jdbc);
        if (savedBinding == null || !Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) denied();
        org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(savedBinding.getId());
        if (savedBinding.getPatientId() != null) org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(savedBinding.getPatientId());
        if (savedBinding.getUserId() != null) org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(savedBinding.getUserId());
        requireAbsent(savedBinding.getId());
        requireParents(savedBinding,true,false);
        if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) denied();
        requireAbsent(savedBinding.getId());
    }
    private void requireAbsent(long id) {
        if (!jdbc.query(connection -> {
            java.sql.PreparedStatement statement=connection.prepareStatement("SELECT id FROM medical_record WHERE id=? LIMIT 2");
            statement.setQueryTimeout(5);statement.setLong(1,id);return statement;
        },(result,index)->result.getLong("id")).isEmpty()) denied();
    }

    /** 聚焦来源严格沿用照护证据权限；管理员或任务指派不能替代该权限。 */
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
    public void requireCurrentEvidence(MedicalRecord snapshot, long actor, long patient) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,patient_id,user_id FROM medical_record WHERE id=?", snapshot.getId());
        if (rows.size() != 1 || !Objects.equals(number(rows.get(0).get("id")), snapshot.getId())
                || !Objects.equals(number(rows.get(0).get("patient_id")), snapshot.getPatientId())
                || !Objects.equals(number(rows.get(0).get("user_id")), snapshot.getUserId())
                || !Long.valueOf(patient).equals(snapshot.getPatientId())
                || !Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())
                || !carePlanAuthorization.canReadEvidence(actor, patient, "MEDICAL_RECORD", snapshot.getId())) denied();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.datasource.DataSourceTransactionManager readTransactions;
    @org.springframework.beans.factory.annotation.Autowired
    private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection readValidity;
    @org.springframework.beans.factory.annotation.Autowired
    private org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader readQualification;

    /** Read admission is independent of the caller's content snapshot and cached request roles. */
    public void requireReadAdmission(Collection<MedicalRecord> records, long actor) {
        List<ReadSource> sources=readSources(records);
        currentRead(sources,null,actor,null,false);
    }

    /** The focused evidence predicate never inherits general administrator permission. */
    public void requireEvidenceAdmission(MedicalRecord record, long actor, long patient) {
        currentRead(readSources(Collections.singletonList(record)),null,actor,patient,false);
    }

    public Map<Long,State> currentReadFrame(Collection<MedicalRecord> records, Map<Long,State> snapshotStates, long actor) {
        List<ReadSource> sources=readSources(records);
        return currentRead(sources,readExpected(sources,snapshotStates),actor,null,true);
    }

    public State currentEvidenceFrame(MedicalRecord record, State snapshotState, long actor, long patient) {
        List<ReadSource> sources=readSources(Collections.singletonList(record));
        Map<Long,State> expected=Collections.singletonMap(sources.get(0).id,snapshotState);
        return currentRead(sources,readExpected(sources,expected),actor,patient,true).get(sources.get(0).id);
    }

    private Map<Long,State> currentRead(List<ReadSource> sources, Map<Long,State> expected,
            long actor, Long patient, boolean qualify) {
        readActor(actor);readGraph();
        ReadResource outer=readOuter();
        try {
            org.springframework.transaction.support.TransactionTemplate template=new org.springframework.transaction.support.TransactionTemplate(readTransactions);
            template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            template.setReadOnly(true);
            Map<Long,State> result=template.execute(status->{
                ReadResource resource=new ReadResource(java.sql.Connection.TRANSACTION_READ_COMMITTED);
                if(outer!=null&&(resource.holder==outer.holder||resource.connection==outer.connection))throw readUnavailable();
                Map<Long,State> frame=new LinkedHashMap<>();
                if(sources.isEmpty()) {
                    readAuthority(Collections.emptyList(),actor,patient,resource);
                    if(qualify) {
                        readActor(actor);resource.verify();
                        Map<Long,State> empty=readQualified(Collections.emptyList(),resource);
                        resource.verify();if(empty==null||!empty.isEmpty())throw readChanged();
                    }
                } else for(int offset=0;offset<sources.size();offset+=100) {
                    List<ReadSource> part=sources.subList(offset,Math.min(offset+100,sources.size()));
                    readAuthority(part,actor,patient,resource);
                    if(qualify) {
                        readActor(actor);resource.verify();
                        List<Long> ids=new ArrayList<>();for(ReadSource source:part)ids.add(source.id);
                        Map<Long,State> observed=readQualified(ids,resource);
                        resource.verify();readActor(actor);
                        if(observed==null||observed.size()!=part.size()||!observed.keySet().equals(new LinkedHashSet<>(ids)))throw readChanged();
                        for(ReadSource source:part) {
                            State state=observed.get(source.id);
                            readState(source,state,org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics.CURRENT_CHECK);
                            if(!state.signature.equals(expected.get(source.id).signature))throw readChanged();
                            frame.put(source.id,state);
                        }
                    }
                }
                // Every frame finishes a second whole-source authority sweep, including the empty actor check.
                if(qualify) {
                    readActor(actor);resource.verify();
                    if(sources.isEmpty())readAuthority(Collections.emptyList(),actor,patient,resource);
                    else for(int offset=0;offset<sources.size();offset+=100)
                        readAuthority(sources.subList(offset,Math.min(offset+100,sources.size())),actor,patient,resource);
                }
                readActor(actor);resource.verify();
                return Collections.unmodifiableMap(frame);
            });
            if(result==null)throw readUnavailable();
            readActor(actor);return result;
        } catch(org.familyhealthcare.service.workflow.WorkflowException|ReadDenied failure) {
            throw failure;
        } catch(RuntimeException failure) {
            // New query/transaction failures are not authorization or schema diagnoses, and carry no cause.
            throw readUnavailable();
        } finally {
            if(outer!=null)outer.verify();
            else if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    ||org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                    ||org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw readUnavailable();
        }
    }

    private Map<Long,State> readQualified(Collection<Long> ids,ReadResource resource) {
        try {return readValidity.observeQualified(ids);}
        catch(org.familyhealthcare.service.workflow.WorkflowException failure) {
            // A reader diagnosis is retained only while its actual current resource remains valid.
            resource.verify();throw failure;
        }
    }

    private void readAuthority(List<ReadSource> sources,long actor,Long focusedPatient,ReadResource resource) {
        readActor(actor);resource.verify();
        if(focusedPatient!=null) {
            if(sources.size()!=1||!Objects.equals(sources.get(0).patient,focusedPatient))throw new ReadDenied();
            readHeaders(sources,resource);
            if(carePlanAuthorization==null)throw readUnavailable();
            if(!carePlanAuthorization.canReadEvidence(actor,focusedPatient,"MEDICAL_RECORD",sources.get(0).id))throw new ReadDenied();
            readActor(actor);resource.verify();return;
        }
        boolean[] roles=readActorRow(actor,resource);
        if(sources.isEmpty())return;
        readHeaders(sources,resource);
        LinkedHashSet<Long> patients=new LinkedHashSet<>();for(ReadSource source:sources)if(source.patient!=null)patients.add(source.patient);
        if(patients.isEmpty()) {
            for(ReadSource source:sources)if(!roles[0]&&!Objects.equals(source.owner,actor))throw new ReadDenied();
            return;
        }
        String marks=readMarks(patients.size());List<Object> args=new ArrayList<>(patients);
        Map<Long,ReadPatient> contexts=readQuery(resource,"SELECT id,user_id,status,deleted FROM patient WHERE id IN ("+marks+") ORDER BY id LIMIT "+(patients.size()+1),args,rs->{
            Map<Long,ReadPatient> rows=new LinkedHashMap<>();
            while(rs.next()) {
                long id=rs.getLong("id");
                if(!patients.contains(id)||rows.containsKey(id))throw new ReadDenied();
                Long status=readNumber(rs,"status"),deleted=readNumber(rs,"deleted");
                if(!Long.valueOf(1).equals(status)||deleted!=null&&deleted!=0)throw new ReadDenied();
                rows.put(id,new ReadPatient(readNumber(rs,"user_id")));
            }
            if(rows.size()!=patients.size())throw new ReadDenied();return rows;
        });
        List<Object> scoped=new ArrayList<>();scoped.add(actor);scoped.addAll(patients);
        readQuery(resource,"SELECT DISTINCT patient_id FROM doctor_patient_assignment WHERE doctor_user_id=? AND patient_id IN ("+marks+") AND status='ACTIVE' ORDER BY patient_id LIMIT "+(patients.size()+1),scoped,rs->{
            while(rs.next()){ReadPatient context=contexts.get(rs.getLong("patient_id"));if(context==null)throw readChanged();context.assigned=true;}return null;
        });
        // Complete fanout: bounded per-patient booleans, no cap on grant/member rows or role strings.
        readQuery(resource,"SELECT patient_id,access_level,visible_modules,status,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired FROM care_access_grant WHERE grantee_user_id=? AND patient_id IN ("+marks+") ORDER BY patient_id,id",scoped,rs->{
            while(rs.next()) {
                ReadPatient context=contexts.get(rs.getLong("patient_id"));if(context==null)throw readChanged();context.hasGrant=true;
                Map<String,Object> permission=new HashMap<>();permission.put("access_level",rs.getObject("access_level"));permission.put("visible_modules",rs.getString("visible_modules"));
                if("ACTIVE".equals(rs.getString("status"))&&truth(rs.getObject("unexpired"))&&permits(permission,false))context.granted=true;
            }return null;
        });
        readQuery(resource,"SELECT patient_id,access_level,visible_modules FROM care_member WHERE user_id=? AND patient_id IN ("+marks+") ORDER BY patient_id,id",scoped,rs->{
            while(rs.next()) {
                ReadPatient context=contexts.get(rs.getLong("patient_id"));if(context==null)throw readChanged();
                Map<String,Object> permission=new HashMap<>();permission.put("access_level",rs.getObject("access_level"));permission.put("visible_modules",rs.getString("visible_modules"));
                if(permits(permission,false))context.member=true;
            }return null;
        });
        for(ReadSource source:sources) {
            if(source.patient==null){if(!roles[0]&&!Objects.equals(source.owner,actor))throw new ReadDenied();continue;}
            ReadPatient context=contexts.get(source.patient);
            if(!roles[0]&&!(roles[1]&&context.assigned)&&!Objects.equals(context.owner,actor)
                    &&!(context.hasGrant?context.granted:context.member))throw new ReadDenied();
        }
        readActor(actor);resource.verify();
    }

    private boolean[] readActorRow(long actor,ReadResource resource) {
        return readQuery(resource,"SELECT u.id,u.status,u.deleted,"
                +"EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.status=1 AND COALESCE(r.deleted,0)=0) AS any_role,"
                +"EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.status=1 AND COALESCE(r.deleted,0)=0 AND r.role_code='admin') AS admin_role,"
                +"EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.status=1 AND COALESCE(r.deleted,0)=0 AND r.role_code='doctor') AS doctor_role "
                +"FROM sys_user u WHERE u.id=? LIMIT 2",Collections.singletonList(actor),rs->{
            if(!rs.next()||rs.getLong("id")!=actor||!Long.valueOf(1).equals(readNumber(rs,"status")))throw new ReadDenied();
            Long deleted=readNumber(rs,"deleted");if(deleted!=null&&deleted!=0||!truth(rs.getObject("any_role")))throw new ReadDenied();
            boolean[] roles={truth(rs.getObject("admin_role")),truth(rs.getObject("doctor_role"))};
            if(rs.next())throw new ReadDenied();return roles;
        });
    }

    private void readHeaders(List<ReadSource> sources,ReadResource resource) {
        Map<Long,ReadSource> expected=new LinkedHashMap<>();for(ReadSource source:sources)expected.put(source.id,source);
        readQuery(resource,"SELECT id,patient_id,user_id,content_version,verification_status FROM medical_record WHERE id IN ("+readMarks(sources.size())+") ORDER BY id LIMIT "+(sources.size()+1),new ArrayList<>(expected.keySet()),rs->{
            Set<Long> seen=new HashSet<>();while(rs.next()) {
                long id=rs.getLong("id");ReadSource source=expected.get(id);
                if(source==null||!seen.add(id)||!Objects.equals(source.patient,readNumber(rs,"patient_id"))
                        ||!Objects.equals(source.owner,readNumber(rs,"user_id")))throw new ReadDenied();
                if(!Objects.equals(source.version,readNumber(rs,"content_version"))||!Objects.equals(source.review,rs.getString("verification_status")))throw readChanged();
            }
            if(seen.size()!=sources.size())throw new ReadDenied();return null;
        });
    }

    private <T> T readQuery(ReadResource resource,String sql,List<?> args,org.springframework.jdbc.core.ResultSetExtractor<T> extract) {
        resource.verify();
        try {
            T result=jdbc.query(sql,statement->{
                resource.verify();statement.setQueryTimeout(resource.holder.hasTimeout()?Math.min(5,resource.holder.getTimeToLiveInSeconds()):5);
                for(int index=0;index<args.size();index++)statement.setObject(index+1,args.get(index));
            },extract);
            resource.verify();return result;
        } catch(org.familyhealthcare.service.workflow.WorkflowException|ReadDenied failure){throw failure;
        } catch(RuntimeException failure){throw readUnavailable();}
    }

    private static String readMarks(int size) {
        if(size<1||size>100)throw readChanged();return String.join(",",Collections.nCopies(size,"?"));
    }
    private static Long readNumber(java.sql.ResultSet rows,String column)throws java.sql.SQLException {
        long value=rows.getLong(column);return rows.wasNull()?null:value;
    }
    private static List<ReadSource> readSources(Collection<MedicalRecord> records) {
        if(records==null)throw readChanged();Map<Long,ReadSource> unique=new LinkedHashMap<>();
        for(MedicalRecord record:records) {
            ReadSource source=new ReadSource(record),prior=unique.putIfAbsent(source.id,source);
            if(prior!=null&&!prior.same(source))throw readChanged();
        }return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }
    private static Map<Long,State> readExpected(List<ReadSource> sources,Map<Long,State> states) {
        if(states==null||states.size()!=sources.size())throw readChanged();Map<Long,State> result=new LinkedHashMap<>();
        for(ReadSource source:sources) {
            State state=states.get(source.id);
            readState(source,state,org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT);
            result.put(source.id,state);
        }
        if(!result.keySet().equals(states.keySet()))throw readChanged();return Collections.unmodifiableMap(result);
    }
    private static void readState(ReadSource source,State state,org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics semantics) {
        if(state==null||!state.reviewObserved||state.signature==null||state.checkedAt==null||state.qualificationSemantics!=semantics
                ||state.id!=source.id||!Objects.equals(source.patient,state.patientId)||!Objects.equals(source.owner,state.ownerId)
                ||!Objects.equals(source.version,state.version)||!Objects.equals(source.review,state.review))throw readChanged();
    }
    private static void readActor(long actor) {
        if(actor<=0||actor>9007199254740991L||!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId()))throw new ReadDenied();
    }
    private void readGraph() {
        try {
            if(jdbc.getDataSource()==null||readTransactions==null||readTransactions.getDataSource()!=jdbc.getDataSource()
                    ||readQualification==null||readValidity==null)throw readUnavailable();
            requireDataSource(jdbc.getDataSource());readQualification.requireDataSource(jdbc.getDataSource());
        }catch(RuntimeException failure){throw readUnavailable();}
    }
    private ReadResource readOuter() {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())return new ReadResource(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                ||org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw readUnavailable();
        return null;
    }
    private final class ReadResource {
        final Thread thread=Thread.currentThread();final int isolation;
        final org.springframework.jdbc.datasource.ConnectionHolder holder;
        final org.springframework.jdbc.datasource.SimpleConnectionHandle handle;final java.sql.Connection connection;
        ReadResource(int isolation) {
            this.isolation=isolation;readGraph();
            Object value=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    ||!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                    ||value==null||value.getClass()!=org.springframework.jdbc.datasource.ConnectionHolder.class)throw readUnavailable();
            holder=(org.springframework.jdbc.datasource.ConnectionHolder)value;
            if(!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()==null
                    ||holder.getConnectionHandle().getClass()!=org.springframework.jdbc.datasource.SimpleConnectionHandle.class)throw readUnavailable();
            handle=(org.springframework.jdbc.datasource.SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();verify();
        }
        void verify() {
            readGraph();
            if(Thread.currentThread()!=thread||!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    ||!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                    ||!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource())!=holder
                    ||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw readUnavailable();
            try {
                if(connection==null||holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()
                        ||connection.getTransactionIsolation()!=isolation)throw readUnavailable();
            }catch(java.sql.SQLException|IllegalStateException failure){throw readUnavailable();}
        }
    }
    private static final class ReadSource {
        final long id;final Long patient,owner,version;final String review;
        ReadSource(MedicalRecord record) {
            if(record==null||!readId(record.getId())||record.getPatientId()!=null&&!readId(record.getPatientId())
                    ||record.getUserId()!=null&&!readId(record.getUserId())||!readId(record.getContentVersion()))throw readChanged();
            id=record.getId();patient=record.getPatientId();owner=record.getUserId();version=record.getContentVersion();review=record.getVerificationStatus();
        }
        boolean same(ReadSource other){return id==other.id&&Objects.equals(patient,other.patient)&&Objects.equals(owner,other.owner)&&Objects.equals(version,other.version)&&Objects.equals(review,other.review);}
    }
    private static boolean readId(Long value){return value!=null&&value>0&&value<=9007199254740991L;}
    private static final class ReadPatient {
        final Long owner;boolean assigned,hasGrant,granted,member;
        ReadPatient(Long owner){this.owner=owner;}
    }
    private static final class ReadDenied extends IllegalStateException {ReadDenied(){super("Access denied to source record.");}}
    private static org.familyhealthcare.service.workflow.WorkflowException readUnavailable(){return new org.familyhealthcare.service.workflow.WorkflowException(503,"CURRENT_RESULT_UNAVAILABLE","The current medical record result could not be verified.");}
    private static org.familyhealthcare.service.workflow.WorkflowException readChanged(){return new org.familyhealthcare.service.workflow.WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","The current medical record result could not be verified.");}

    private boolean permits(Map<String,Object> row,boolean write) {
        if(write && !Arrays.asList("WRITE","PROXY").contains(String.valueOf(row.get("access_level")))) return false;
        String modules=row.get("visible_modules")==null?"":String.valueOf(row.get("visible_modules")).trim();
        if(modules.isEmpty()) return true;
        for(String module:modules.split(",")) if("MEDICAL".equalsIgnoreCase(module.trim())) return true;
        return false;
    }
    private boolean role(long actor,String role) {return count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0",actor,role)>0;}
    private int count(String sql,Object...args) {return jdbc.queryForObject(sql,Integer.class,args);}
    private static Long number(Object value) {return value==null?null:((Number)value).longValue();}
    private static boolean truth(Object value) {return Boolean.TRUE.equals(value) || value instanceof Number && ((Number)value).intValue()!=0;}
    private static void denied() {throw new IllegalStateException("Access denied to source record.");}
}
