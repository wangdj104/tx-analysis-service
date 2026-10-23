package org.familyhealthcare.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Isolated database restores retain history but must never resume old grants or scheduled delivery. */
@Component
public class RestoreQuarantineGuard implements SmartInitializingSingleton {
    @Autowired private JdbcTemplate jdbc;

    @Override public void afterSingletonsInstantiated() {
        Boolean marked = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (java.sql.ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE", "BASE TABLE"})) {
                while (tables.next()) if ("restore_quarantine".equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                return false;
            }
        });
        if (Boolean.TRUE.equals(marked)) {
            Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM restore_quarantine WHERE active<>0", Integer.class);
            if (active != null && active > 0) throw new IllegalStateException(
                    "RESTORE_QUARANTINED: this independent restore cannot start the application or replay old authorizations and notifications. Keep it isolated for recovery review.");
        }
    }
}
