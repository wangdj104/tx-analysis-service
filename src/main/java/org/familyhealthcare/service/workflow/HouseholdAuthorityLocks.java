package org.familyhealthcare.service.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import java.sql.Connection;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 父锁只稳定当前身份，绝不授予权限。调用方仍须在等待后、写入前和回执披露前验证当前授权。 */
@Component
public final class HouseholdAuthorityLocks {
    public static final class LockedAuthority {
        private final long patientId;
        private final List<Long> userIds,roleIds;
        private final Connection connection;
        private final Thread thread = Thread.currentThread();
        private volatile boolean live = true;
        private LockedAuthority(long patientId,Collection<Long> userIds,Collection<Long> roleIds,Connection connection){
            this.connection = connection;
            this.patientId=patientId;
            this.userIds=Collections.unmodifiableList(new ArrayList<>(userIds));
            this.roleIds=Collections.unmodifiableList(new ArrayList<>(roleIds));
        }
        public long getPatientId(){return patientId;}
        public List<Long> getUserIds(){return userIds;}
        /** IDs selected for locking, never proof of current role membership or permission. */
        public List<Long> getRoleIds(){return roleIds;}
        void requireLive(JdbcTemplate jdbc, long expectedPatient, long actorId) {
            if (!userIds.contains(actorId)) throw stale();
            requireLiveTransaction(jdbc,expectedPatient);
        }
        private void requireLiveTransaction(JdbcTemplate jdbc,long expectedPatient) {
            if (!live || thread!=Thread.currentThread() || patientId!=expectedPatient) throw stale();
            try { WorkflowData.mutationTransaction(jdbc); }
            catch (RuntimeException failure) { throw stale(); }
            Connection current = DataSourceUtils.getConnection(Objects.requireNonNull(jdbc.getDataSource()));
            try { if (current!=connection) throw stale(); }
            finally { DataSourceUtils.releaseConnection(current,jdbc.getDataSource()); }
        }
        private static WorkflowException stale() {
            return new WorkflowException(409,"ADMISSION_CONTEXT_CHANGED","Refresh and retry within the current transaction.");
        }
    }
    /** 只供服务器记录家庭截止事件；不能作为任何人类命令、读正文或通知重试的授权。 */
    public static final class FamilyDueAuthority {
        private final LockedAuthority authority;private final long assistanceId;private final int cycle;private final java.time.Instant deadline;private final Long eventId,notificationId;private final long validationDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        private FamilyDueAuthority(LockedAuthority authority,long assistanceId,int cycle,java.time.Instant deadline,Long eventId,Long notificationId){this.authority=authority;this.assistanceId=assistanceId;this.cycle=cycle;this.deadline=deadline;this.eventId=eventId;this.notificationId=notificationId;}
        void requireLive(JdbcTemplate jdbc,long patient,long assistance,int expectedCycle,java.time.Instant exactDeadline){authority.requireLiveTransaction(jdbc,patient);if(System.nanoTime()>validationDeadline||assistanceId!=assistance||cycle!=expectedCycle||!deadline.equals(exactDeadline))throw LockedAuthority.stale();}
        void requireCreationLive(JdbcTemplate jdbc,long patient,long assistance,int cycle,java.time.Instant deadline){requireLive(jdbc,patient,assistance,cycle,deadline);if(eventId!=null||notificationId!=null)throw LockedAuthority.stale();}
        void requireNotificationLive(JdbcTemplate jdbc,long patient,long assistance,int cycle,java.time.Instant deadline,long event,long notification){requireLive(jdbc,patient,assistance,cycle,deadline);if(!Objects.equals(eventId,event)||!Objects.equals(notificationId,notification))throw LockedAuthority.stale();}
        long validationDeadline(){return validationDeadline;}
    }
    public FamilyDueAuthority lockFamilyDue(long patient,long assistance,int cycle,java.time.Instant deadline,Collection<Long> subjects){positive(assistance);if(cycle<1||deadline==null)throw WorkflowException.invalid("Invalid family deadline.");return new FamilyDueAuthority(lockPatientAndSubjects(patient,subjects),assistance,cycle,deadline,null,null);}
    public FamilyDueAuthority lockFamilyDueNotification(long patient,long assistance,int cycle,java.time.Instant deadline,long event,long notification,Collection<Long> subjects){positive(assistance);positive(event);positive(notification);if(cycle<1||deadline==null)throw WorkflowException.invalid("Invalid family deadline notification.");return new FamilyDueAuthority(lockPatientAndSubjects(patient,subjects),assistance,cycle,deadline,event,notification);}
    /** 只绑定一条真实用药系统事件的通知入场，不承载任何人类命令权限。 */
    public static final class MedicationNotificationAuthority {
        private final LockedAuthority authority;private final long execution,event,notification,deadline;
        private MedicationNotificationAuthority(LockedAuthority authority,long execution,long event,long notification,long deadline){this.authority=authority;this.execution=execution;this.event=event;this.notification=notification;this.deadline=deadline;}
        void requireLive(JdbcTemplate jdbc,long patient,long execution,long event,long notification){authority.requireLiveTransaction(jdbc,patient);if(this.execution!=execution||this.event!=event||this.notification!=notification||System.nanoTime()>deadline)throw LockedAuthority.stale();}
        long validationDeadline(){return deadline;}
    }
    public MedicationNotificationAuthority lockMedicationNotification(long patient,long execution,long event,long notification,Collection<Long> subjects,long deadline){positive(execution);positive(event);positive(notification);return new MedicationNotificationAuthority(lockPatientAndSubjects(patient,subjects),execution,event,notification,Math.min(deadline,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)));}
    private final JdbcTemplate jdbc;
    /** Identity only: never acquire or bind another datasource. */
    public void requireDataSource(javax.sql.DataSource expected) {
        if (expected == null || jdbc.getDataSource() != expected)
            throw new IllegalStateException("The configured datasource does not match.");
    }

    public HouseholdAuthorityLocks(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public void requireMutationTransaction(){WorkflowData.mutationTransaction(jdbc);}
    public LockedAuthority lockPatientAndSubjects(long patientId,Collection<Long> subjectIds){
        requireMutationTransaction();
        positive(patientId);
        List<Map<String,Object>> patients=jdbc.queryForList("SELECT id,user_id FROM patient WHERE id=? FOR UPDATE",patientId);
        if(patients.size()!=1)throw unavailable();
        SortedSet<Long> users=ids(subjectIds);
        Object owner=patients.get(0).get("user_id");
        if(owner instanceof Number)users.add(((Number)owner).longValue());
        lockUsers(users);
        SortedSet<Long> roles=currentRoles(users);
        lockRoles(roles,false);
        lockMappings(users);
        // 固定父锁覆盖尚不存在的授权/成员行；不依赖 gap lock 或原生表中不存在的 FK。
        for(long user:users)jdbc.queryForList("SELECT id FROM care_access_grant WHERE patient_id=? AND grantee_user_id=? ORDER BY id FOR UPDATE",patientId,user);
        for(long user:users)jdbc.queryForList("SELECT id FROM doctor_patient_assignment WHERE patient_id=? AND doctor_user_id=? ORDER BY id FOR UPDATE",patientId,user);
        for(long user:users)jdbc.queryForList("SELECT id FROM care_member WHERE patient_id=? AND user_id=? ORDER BY id FOR UPDATE",patientId,user);
        Connection connection = DataSourceUtils.getConnection(Objects.requireNonNull(jdbc.getDataSource()));
        LockedAuthority token;
        try { token = new LockedAuthority(patientId,users,roles,connection); }
        finally { DataSourceUtils.releaseConnection(connection,jdbc.getDataSource()); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { token.live = false; }
        });
        return token;
    }
    /** 仅供护理分派治理：首次验权前稳定身份，不产生普通工作流写令牌。 */
    public void lockNurseAssignmentSubjects(long patientId,long adminId,long nurseId) {
        requireMutationTransaction();
        positive(patientId);
        SortedSet<Long> users=ids(Arrays.asList(adminId,nurseId));
        List<Map<String,Object>> patients=jdbc.queryForList("SELECT id,user_id FROM patient WHERE id=? LOCK IN SHARE MODE",patientId);
        if(patients.size()!=1)throw unavailable();
        Object owner=patients.get(0).get("user_id");
        if(owner instanceof Number)users.addAll(ids(Collections.singleton(((Number)owner).longValue())));
        lockUsers(users);
        SortedSet<Long> roles=currentRoles(users);
        lockRoles(roles,false);
        lockMappings(users);
        // 等待角色锁时删除可能已完成；集合变化就退出，不能在锁尾追加身份。
        List<Map<String,Object>> current=jdbc.queryForList("SELECT id,user_id FROM patient WHERE id=?",patientId);
        if(current.size()!=1 || !Objects.equals(owner,current.get(0).get("user_id")) || !roles.equals(currentRoles(users)))
            throw unavailable();
    }
    /** 无患者旧病历只锁实际账号和角色；不能捏造 patient 父记录。锁本身不授予访问权限。 */
    public void lockSubjectsWithoutPatient(Collection<Long> subjectIds) {
        requireMutationTransaction();
        SortedSet<Long> users = ids(subjectIds);
        if (users.isEmpty()) throw unavailable();
        lockUsers(users);
        lockRoles(currentRoles(users), false);
        lockMappings(users);
    }
    /** Server-side continuations may validate, but cannot manufacture, the private live token. */
    public void requireLive(LockedAuthority token,long patient,long actor){if(token==null)throw unavailable();token.requireLive(jdbc,patient,actor);}
    /** Role writers never acquire a patient lock after identity locks. */
    public void lockRoleMappingsForUser(long userId,Collection<Long> nextRoleIds){
        requireMutationTransaction();
        positive(userId);
        SortedSet<Long> users=ids(Collections.singleton(userId));
        lockUsers(users);
        SortedSet<Long> roles=currentRoles(users);
        SortedSet<Long> next=ids(nextRoleIds);
        roles.addAll(next);
        lockRoles(roles,false);
        // 删除者可能在角色锁等待期间完成；重新检查下一组角色，避免无 FK 的悬空映射。
        for (long role:next) {
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM sys_role WHERE id=? AND COALESCE(deleted,0)=0",Integer.class,role);
            if (!Integer.valueOf(1).equals(count)) throw unavailable();
        }
        lockMappings(users);
    }
    /** Starts at role, then mappings; never goes backward to patient/user, so assignment cannot deadlock on a user parent. */
    public void lockRoleForDeletion(long roleId){
        requireMutationTransaction();
        positive(roleId);
        lockRoles(ids(Collections.singleton(roleId)),true);
        jdbc.queryForList("SELECT id FROM sys_user_role WHERE role_id=? ORDER BY user_id,id FOR UPDATE",roleId);
        jdbc.queryForList("SELECT id FROM sys_role_menu WHERE role_id=? ORDER BY id FOR UPDATE",roleId);
    }
    private void lockUsers(SortedSet<Long> users){
        for(long id:users)if(jdbc.queryForList("SELECT id FROM sys_user WHERE id=? FOR UPDATE",Long.class,id).size()!=1)throw unavailable();
    }
    private SortedSet<Long> currentRoles(SortedSet<Long> users){
        SortedSet<Long> roles=new TreeSet<>();
        for(long id:users)roles.addAll(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=? ORDER BY role_id",Long.class,id));
        return roles;
    }
    private void lockRoles(SortedSet<Long> roles,boolean requireExisting){
        for (long id:roles) {
            List<Long> found=jdbc.queryForList("SELECT id FROM sys_role WHERE id=? FOR UPDATE",Long.class,id);
            if (requireExisting && found.size()!=1) throw unavailable();
        }
    }
    private void lockMappings(SortedSet<Long> users){for(long id:users)jdbc.queryForList("SELECT id FROM sys_user_role WHERE user_id=? ORDER BY role_id,id FOR UPDATE",Long.class,id);}
    private static SortedSet<Long> ids(Collection<Long> values) {
        SortedSet<Long> result=new TreeSet<>();
        if (values!=null) for (Long value:values) {
            if (value==null) throw WorkflowException.invalid("Invalid identity.");
            positive(value);
            result.add(value);
        }
        return result;
    }
    private static void positive(long id){if(id<=0||id>9007199254740991L)throw WorkflowException.invalid("Invalid identity.");}
    private static WorkflowException unavailable(){return new WorkflowException(409,"AUTHORITY_CHANGED","Current authority changed; refresh and retry.");}
}
