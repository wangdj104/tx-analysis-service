package org.familyhealthcare.service.alertworkflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fictional, minimal relational fixtures only. H2 is not native MySQL acceptance. */
public final class AlertWorkflowTestDatabase {
    public static final Instant NOW=Instant.parse("2026-10-13T12:00:00.123456Z");
    public final JdbcTemplate jdbc;
    public final TransactionTemplate tx;
    public final DataSourceTransactionManager manager;
    public AlertWorkflowTestDatabase() {this(false);}
    /** Only the real medical-reference harness needs this explicit H2 dialect adapter. */
    AlertWorkflowTestDatabase(boolean medicalReader) {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:alert_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"+(medicalReader?";DATABASE_TO_LOWER=TRUE":""),"sa","") {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {return adapt(super.getConnection(),medicalReader);}
            @Override public java.sql.Connection getConnection(String user,String password) throws java.sql.SQLException {return adapt(super.getConnection(user,password),medicalReader);}
        };
        jdbc=new JdbcTemplate(ds);
        for(String[] function:new String[][]{{"JSON_EXTRACT","jsonExtract"},{"JSON_UNQUOTE","jsonUnquote"},{"JSON_LENGTH","jsonLength"},{"JSON_TYPE","jsonType"},{"MICROSECOND","microsecond"},{"DATE_FORMAT","dateFormat"}})jdbc.execute("CREATE ALIAS "+function[0]+" FOR \"org.familyhealthcare.service.alertworkflow.AlertWorkflowSqlPredicates."+function[1]+"\"");
        manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,username VARCHAR(50),real_name VARCHAR(50),status INT,deleted INT)");
        jdbc.execute("CREATE TABLE sys_role(id BIGINT PRIMARY KEY,role_code VARCHAR(30),status INT,deleted INT)");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        jdbc.execute("CREATE TABLE patient(id BIGINT PRIMARY KEY,user_id BIGINT,status INT,deleted INT)");
        jdbc.execute("CREATE TABLE doctor_patient_assignment(doctor_user_id BIGINT,patient_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE care_nurse_assignment(id BIGINT PRIMARY KEY,patient_id BIGINT,nurse_user_id BIGINT,status VARCHAR(20),expires_at TIMESTAMP(6),revoked_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE care_access_grant(id BIGINT AUTO_INCREMENT PRIMARY KEY,patient_id BIGINT,grantee_user_id BIGINT,grantee_role VARCHAR(30),access_level VARCHAR(20),visible_modules VARCHAR(1000),status VARCHAR(20),expires_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE medical_record(id BIGINT PRIMARY KEY,patient_id BIGINT,user_id BIGINT,content_version BIGINT DEFAULT 1,verification_status VARCHAR(30) DEFAULT 'REVIEW_REQUIRED')");
        jdbc.execute("CREATE TABLE doctor_care_plan(id BIGINT PRIMARY KEY,patient_id BIGINT,current_revision_id BIGINT,status VARCHAR(20),workflow_version INT,lifecycle VARCHAR(20))");
        jdbc.execute("CREATE TABLE care_plan_action(id BIGINT PRIMARY KEY,plan_id BIGINT,patient_id BIGINT,revision_id BIGINT,status VARCHAR(20),lock_version BIGINT)");
        jdbc.execute("CREATE TABLE care_plan_event(id BIGINT PRIMARY KEY,plan_id BIGINT,action_id BIGINT,event_type VARCHAR(40),patient_id BIGINT)");
        jdbc.execute("CREATE TABLE care_plan_evidence(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id BIGINT,source_type VARCHAR(30),source_id BIGINT)");
        jdbc.execute("CREATE TABLE patient_health_target(id BIGINT PRIMARY KEY,patient_id BIGINT,systolic_min INT,systolic_max INT,diastolic_min INT,diastolic_max INT,target_weight DECIMAL(12,3),weight_gain_limit DECIMAL(12,3))");
    }
    private static final String CORRECTION_PRESENCE=presenceSql("/medical-record-correction-schema.json");
    private static final String REVIEW_PRESENCE=presenceSql("/medical-review-schema.json");
    private static final String ALERT_ANCHOR="SELECT v.id,a.patient_id,v.source_id AS record_id,NULL AS item_id,e.id AS event_id,a.id AS aggregate_id,NULL AS action_id FROM alert_evidence v FORCE INDEX (PRIMARY) JOIN alert_event e ON e.id=v.event_id JOIN alert_record a ON a.id=e.alert_id AND a.patient_id=e.patient_id AND a.workflow_version=1 AND v.source_type='MEDICAL_RECORD' WHERE v.id IN (?) ORDER BY v.id LIMIT 2";
    private static String presenceSql(String resource) {
        try(java.io.InputStream input=AlertWorkflowTestDatabase.class.getResourceAsStream(resource)) {
            if(input==null)throw new IllegalStateException("Missing medical schema contract");
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(input).get("presenceSql").asText();
        } catch(java.io.IOException failure) {throw new IllegalStateException(failure);}
    }
    /** Exact two metadata statements only. Counts actual H2 objects; never fabricates ABSENT/READY.
     * H2 has no MySQL scheduled EVENT objects; aliases are its routine equivalent.
     * A nonempty footprint proceeds to the unchanged MySQL metadata SQL and fails closed here.
     */
    private static String h2Presence(boolean review) {
        String tables=review?"LEFT(LOWER(TABLE_NAME),15)='medical_review_'":"(TABLE_NAME IN ('medical_record_correction_state','medical_record_correction_history','medical_record_command','medical_record_reference_version') OR LEFT(TABLE_NAME,26)='medical_record_correction_' OR LEFT(TABLE_NAME,22)='medical_record_command' OR LEFT(TABLE_NAME,32)='medical_record_reference_version')";
        String prefix=review?"mrv57_":"mrc57_";
        String sql="SELECT (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=SCHEMA() AND "+tables+")"
                +"+(SELECT COUNT(*) FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA=SCHEMA() AND ("+tables+" OR LEFT(LOWER(TRIGGER_NAME),6)='"+prefix+"'))"
                +"+(SELECT COUNT(*) FROM INFORMATION_SCHEMA.FUNCTION_ALIASES WHERE ALIAS_SCHEMA=SCHEMA() AND LEFT(LOWER(ALIAS_NAME),6)='"+prefix+"')"
                +"+(SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA=SCHEMA() AND LEFT(LOWER(INDEX_NAME),6)='"+prefix+"')"
                +"+(SELECT COUNT(*) FROM INFORMATION_SCHEMA.CONSTRAINTS WHERE CONSTRAINT_SCHEMA=SCHEMA() AND LEFT(LOWER(CONSTRAINT_NAME),6)='"+prefix+"')";
        if(review)sql+="+(SELECT COUNT(*) FROM INFORMATION_SCHEMA.CROSS_REFERENCES WHERE PKTABLE_SCHEMA=SCHEMA() AND LEFT(LOWER(PKTABLE_NAME),15)='medical_review_' AND NOT (FKTABLE_SCHEMA=SCHEMA() AND LEFT(LOWER(FKTABLE_NAME),15)='medical_review_'))";
        return sql+" AS present";
    }
    private static java.sql.Connection adapt(java.sql.Connection connection,boolean medicalReader) {
        if(!medicalReader)return connection;
        return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(AlertWorkflowTestDatabase.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args!=null&&args.length>0&&args[0] instanceof String) {
                String sql=(String)args[0];
                if(sql.equals(CORRECTION_PRESENCE))args[0]=h2Presence(false);
                else if(sql.equals(REVIEW_PRESENCE))args[0]=h2Presence(true);
                else if(sql.equals(ALERT_ANCHOR))args[0]=ALERT_ANCHOR.replace(" FORCE INDEX (PRIMARY)","");
            }
            try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
    }
    public void fixtures() {
        jdbc.update("INSERT INTO sys_role VALUES(1,'patient',1,0),(2,'family',1,0),(3,'doctor',1,0),(4,'nurse',1,0),(5,'admin',1,0)");
        for(int id=1;id<=6;id++)jdbc.update("INSERT INTO sys_user VALUES(?,?,?,1,0)",id,"fictional"+id,"Fictional "+id);
        jdbc.update("INSERT INTO sys_user_role VALUES(1,1),(2,2),(3,3),(4,4),(5,5),(6,1)");
        jdbc.update("INSERT INTO patient VALUES(10,1,1,0),(20,6,1,0)");
        jdbc.update("INSERT INTO doctor_patient_assignment VALUES(3,10,'ACTIVE')");
        jdbc.update("INSERT INTO care_nurse_assignment VALUES(1,10,4,'ACTIVE',NULL,NULL)");
        grant(2,"FAMILY","READ","MEASUREMENTS",null);
        grant(4,"NURSE","WRITE","MEASUREMENTS",null);
    }
    public void grant(long actor,String role,String level,String modules,Instant expiry) {
        jdbc.update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,status,expires_at) VALUES(10,?,?,?,?, 'ACTIVE',?)",actor,role,level,modules,expiry==null?null:java.sql.Timestamp.from(expiry));
    }
    public void sourceTables() throws Exception {
        for(String table:Arrays.asList("health_measurement","bp_self_monitor_record"))jdbc.execute(h2(tableSql(read("src/main/resources/sql/init.sql"),table)));
    }
    public void workflowTables() throws Exception {
        String sql=read("src/main/resources/sql/init.sql");
        for(String table:Arrays.asList("alert_record","alert_event","alert_source_identity","alert_source_revision","alert_source_link","alert_evidence","alert_command","measurement_command","alert_notification"))
            jdbc.execute(h2(tableSql(sql,table)));
    }
    public static String read(String path) throws Exception {return new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8).replace("\r\n","\n");}
    public static String tableSql(String sql,String table) {
        Matcher m=Pattern.compile("CREATE TABLE(?: IF NOT EXISTS)? `"+table+"` \\([\\s\\S]*?\\) ENGINE=[^;]+;").matcher(sql);
        assertTrue(m.find(),table+" storage contract must exist");return m.group();
    }
    public static String h2(String sql) {
        return sql.replaceAll("/\\*!80016[\\s\\S]*?\\*/","").replaceAll(" CHARACTER SET [a-zA-Z0-9_]+","").replaceAll(" COLLATE [a-zA-Z0-9_]+","")
                .replaceAll(" COMMENT '[^']*'","").replaceAll("\\) ENGINE=[^;]+;", ");");
    }
}
