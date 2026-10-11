package org.familyhealthcare.service;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
/** Called only after the existing guarded disposable MySQL gate verifies its test identity. */
public final class HealthAutomationMysqlAssertions {
 private HealthAutomationMysqlAssertions(){}
 public static void verify(DataSource source) throws Exception {HealthAnalysisRunAssertions.verify(new JdbcTemplate(source));}
}
