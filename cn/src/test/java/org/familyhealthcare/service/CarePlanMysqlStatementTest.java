package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Helper/driver-boundary regressions only, not native privilege acceptance.
 * Connector/J 8.0.29 rejects SELECT through executeUpdate before sending SQL
 * (StatementImpl.executeUpdateInternal / Statement.46 / SQLState 01S03 / code0).
 */
class CarePlanMysqlStatementTest {
    @Test void selectPermissionProbePreservesTheServerDenialInsteadOfAClientApiError() {
        String sql = "SELECT COUNT(*) FROM mysql.user";
        SQLException serverDenied = new SQLException("Synthetic SELECT command denied", "42000", 1142);
        SQLException actual = assertThrows(SQLException.class,
                () -> CarePlanMysqlIntegrationTest.execute(boundary(sql, serverDenied), sql));
        assertEquals(1142, actual.getErrorCode(), "The read probe must reach a result-compatible JDBC path");
        assertSame(serverDenied, actual, "The helper must propagate the original native permission error unchanged");
    }

    @Test void resultCompatibleExecutionStillPreservesNativeMutationConstraintErrors() {
        String sql = "UPDATE care_plan_action SET patient_id=999999 WHERE id=7301";
        SQLException nativeFk = new SQLException("Synthetic foreign-key denial", "23000", 1452);
        SQLException actual = assertThrows(SQLException.class,
                () -> CarePlanMysqlIntegrationTest.execute(boundary(sql, nativeFk), sql));
        assertEquals(1452, actual.getErrorCode());
        assertSame(nativeFk, actual);
    }

    private Connection boundary(String sql, SQLException serverFailure) {
        Statement statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) return null;
                    if ("executeUpdate".equals(method.getName()) || "execute".equals(method.getName()) || "executeQuery".equals(method.getName())) {
                        assertEquals(sql, args[0], "The original probe/constraint SQL must reach the driver boundary unchanged");
                        if ("executeUpdate".equals(method.getName()) && sql.startsWith("SELECT"))
                            throw new SQLException("Statement.executeUpdate() cannot issue statements that produce result sets", "01S03", 0);
                        throw serverFailure;
                    }
                    throw new AssertionError("Unexpected Statement boundary operation: " + method.getName());
                });
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) return statement;
                    throw new AssertionError("Unexpected Connection boundary operation: " + method.getName());
                });
    }
}
