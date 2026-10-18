package org.familyhealthcare.service.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;

/** 仅复核原有管理权限；管理员管理关系不代表临床授权或 B 模块授权。必须先取得身份父锁。 */
public final class HouseholdGovernance {
    private HouseholdGovernance() { }

    public static void requireCurrentAccount(JdbcTemplate jdbc, long actorId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND COALESCE(deleted,0)=0", Integer.class, actorId);
        if (!Integer.valueOf(1).equals(count)) throw denied();
    }

    public static void requireCurrentAdmin(JdbcTemplate jdbc, long actorId) {
        requireCurrentAccount(jdbc, actorId);
        if (!isCurrentAdmin(jdbc, actorId)) throw denied();
    }

    public static void requireCurrentOwnerOrAdmin(JdbcTemplate jdbc, long patientId, long actorId) {
        requireCurrentAccount(jdbc, actorId);
        List<Long> owners = jdbc.queryForList("SELECT user_id FROM patient WHERE id=? AND COALESCE(deleted,0)=0", Long.class, patientId);
        if (owners.size()!=1 || (!Long.valueOf(actorId).equals(owners.get(0)) && !isCurrentAdmin(jdbc, actorId))) throw denied();
    }

    private static boolean isCurrentAdmin(JdbcTemplate jdbc, long actorId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code='admin' AND r.status=1 AND COALESCE(r.deleted,0)=0", Integer.class, actorId);
        return count!=null && count>0;
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Access denied: current authority is required.");
    }
}
