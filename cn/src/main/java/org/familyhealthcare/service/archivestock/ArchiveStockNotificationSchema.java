package org.familyhealthcare.service.archivestock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.sql.*;

/** No cache, repair, business scan or swallowed transaction failure. Only exact metadata is READY. */
@Component
public final class ArchiveStockNotificationSchema {
    public enum Status { ABSENT, INCOMPATIBLE, READY }
    private static final JsonNode MANIFEST=manifest();
    public Status inspect(JdbcTemplate jdbc) {
        return jdbc.execute((ConnectionCallback<Status>)connection->inspectStorage(connection));
    }
    public Status inspectStorage(Connection connection) throws SQLException {
        long present=scalar(connection,MANIFEST.get("presenceSql").asText());
        if(present==0)return Status.ABSENT;
        return scalar(connection,MANIFEST.get("metadataSql").asText())==1?Status.READY:Status.INCOMPATIBLE;
    }
    public void requireReady(JdbcTemplate jdbc) { requireReady(inspect(jdbc)); }
    public void requireReady(Status status) {
        if(status!=Status.READY)throw new WorkflowException(503,"ARCHIVE_STOCK_SCHEMA_"+status.name(),
                "Stock reminders are paused until the restored-stock policy schema is ready. Install and verify its SQL before deploying the application.");
    }
    private static long scalar(Connection connection,String sql)throws SQLException {
        try(PreparedStatement p=connection.prepareStatement(sql)) {p.setQueryTimeout(5);try(ResultSet r=p.executeQuery()){
            if(!r.next())throw new SQLException("Missing policy metadata result");long v=r.getLong(1);
            if(r.wasNull()||v<0||r.next())throw new SQLException("Invalid policy metadata result");return v;
        }}
    }
    private static JsonNode manifest(){try(InputStream in=ArchiveStockNotificationSchema.class.getResourceAsStream("/archive-stock-notification-schema.json")){
        if(in==null)throw new IllegalStateException("Missing policy schema contract");JsonNode node=new ObjectMapper().readTree(in);
        if(node.path("contractVersion").asInt()!=1||!node.path("presenceSql").isTextual()||!node.path("metadataSql").isTextual())throw new IllegalStateException("Invalid policy schema contract");return node;
    }catch(java.io.IOException failure){throw new IllegalStateException("Unreadable policy schema contract",failure);}}
}
