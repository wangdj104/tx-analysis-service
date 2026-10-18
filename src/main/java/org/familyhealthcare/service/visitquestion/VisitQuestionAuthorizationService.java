package org.familyhealthcare.service.visitquestion;

import org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 只依据当前数据库授权；记录人身份既不授予权限，也不等于患者本人。 */
@Service
public class VisitQuestionAuthorizationService {
    private final FamilyAssistanceAuthorizationService authority;
    private final JdbcTemplate jdbc;
    public VisitQuestionAuthorizationService(FamilyAssistanceAuthorizationService authority, JdbcTemplate jdbc) {
        this.authority = authority; this.jdbc = jdbc;
    }
    public void read(long actor, long patient) { authority.requireRead(actor, patient, "QUESTION"); }
    public void write(long actor, long patient) { authority.requireWrite(actor, patient, "QUESTION"); }
    public void publish(long actor, long patient) { write(actor, patient); authority.requireDoctor(actor); }
    public long owner(long patient) { return authority.owner(patient); }
    public void assignee(Long actor, long patient) { if (actor != null) write(actor, patient); }
    /** 首次跟踪才尝试以合格的记录所有者为默认负责人；失格时保持未分配。 */
    public Long qualifiedOwner(long patient) {
        long owner=owner(patient);
        try { write(owner,patient); return owner; }
        catch (WorkflowException failure) { if(failure.getStatus()!=403)throw failure; return null; }
    }
    public void ownerWrite(long actor,long patient) {
        write(actor,patient);
        if(owner(patient)!=actor)throw FamilyAssistanceAuthorizationService.denied();
    }
    /** 关闭/重开还需当前所有者、跟踪创建者或负责人关系；专业答复本身不赋予此权。 */
    public void administer(long actor,long patient,java.util.Map<String,Object> state) {
        write(actor,patient);
        // 首次命令的跟踪创建者就是本次真实操作者，不重建历史作者。
        if(state==null||owner(patient)==actor||matches(state.get("created_by"),actor)||matches(state.get("responsible_user_id"),actor))return;
        throw FamilyAssistanceAuthorizationService.denied();
    }
    private static boolean matches(Object value,long actor) { return value instanceof Number&&((Number)value).longValue()==actor; }
    /** 管理员兼容仅适用于已确认未被任何流程接管的普通记录。 */
    public void manual(long actor, long patient) {
        try { write(actor, patient); }
        catch (WorkflowException failure) {
            if (failure.getStatus() != 403) throw failure;
            authority.requireNotificationAdmin(actor);
            Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM patient WHERE id=? AND COALESCE(deleted,0)=0", Integer.class, patient);
            if (!Integer.valueOf(1).equals(present)) throw FamilyAssistanceAuthorizationService.denied();
        }
    }
    /** 仅供旧普通编辑器使用；不改变任何 Q 读写授权，也不接受缓存的管理员角色。 */
    public void ordinaryEditorAdmin(long actor,long patient) {
        authority.requireNotificationAdmin(actor);
        Integer present=jdbc.queryForObject("SELECT COUNT(*) FROM patient WHERE id=? AND COALESCE(deleted,0)=0",Integer.class,patient);
        if(!Integer.valueOf(1).equals(present))throw FamilyAssistanceAuthorizationService.denied();
    }
    public String actorKind(long actor, long patient, boolean professional) {
        if (professional) { publish(actor, patient); return "AUTHORIZED_DOCTOR"; }
        write(actor, patient);
        if (owner(patient) == actor) return "RECORD_OWNER";
        for (String role : new String[]{"family", "doctor", "nurse"}) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role m JOIN sys_role r ON r.id=m.role_id WHERE m.user_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0", Integer.class, actor, role);
            if (count != null && count > 0) return "AUTHORIZED_" + role.toUpperCase(java.util.Locale.ROOT);
        }
        return "OTHER_AUTHORIZED";
    }
}
