package org.familyhealthcare.service.alertworkflow;

import com.alibaba.fastjson2.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes extracted SQL guard predicates against synthetic H2 rows, not native MySQL triggers/DDL. */
public final class AlertWorkflowSqlPredicates {
    private final String init,upgrade,preflight,postcheck;
    AlertWorkflowSqlPredicates() throws Exception {
        init=AlertWorkflowTestDatabase.read("src/main/resources/sql/init.sql");
        upgrade=AlertWorkflowTestDatabase.read("src/main/resources/sql/alert_workflow_upgrade_20261013.sql");
        preflight=AlertWorkflowTestDatabase.read("src/main/resources/sql/alert_workflow_preflight_20261013.sql");
        postcheck=AlertWorkflowTestDatabase.read("src/main/resources/sql/alert_workflow_postcheck_20261013.sql");
    }
    public static String jsonExtract(String json,String path){Object value=com.alibaba.fastjson2.JSONPath.eval(json,path);return value==null?null:JSON.toJSONString(value);}
    public static String jsonUnquote(String value){if(value==null)return null;Object parsed=JSON.parse(value);return parsed instanceof String?(String)parsed:value;}
    public static int jsonLength(String json){Object value=JSON.parse(json);return value instanceof Map?((Map<?,?>)value).size():value instanceof List?((List<?>)value).size():1;}
    public static String jsonType(String json){Object value=JSON.parse(json);return value instanceof Map?"OBJECT":value instanceof List?"ARRAY":value instanceof String?"STRING":value==null?"NULL":"OTHER";}
    public static int microsecond(java.sql.Timestamp time){return time.toLocalDateTime().getNano()/1000;}
    public static String dateFormat(java.sql.Timestamp time,String pattern){String javaPattern;
        if("%Y-%m-%dT%H:%i:%s.%fZ".equals(pattern))javaPattern="uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'";
        else if("%Y-%m-%dT%H:%i:%sZ".equals(pattern))javaPattern="uuuu-MM-dd'T'HH:mm:ss'Z'";
        else if("%Y-%m-%dT%H:%i:%s.".equals(pattern))javaPattern="uuuu-MM-dd'T'HH:mm:ss'.'";
        else throw new IllegalArgumentException("Unsupported synthetic date format");return time.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern(javaPattern));
    }
    public static int jsonValid(String json){return json!=null&&JSON.isValid(json)?1:0;}
    void parity() throws Exception {
        String peer=java.nio.file.Files.exists(java.nio.file.Paths.get("cn/pom.xml"))?"cn/":"../";
        for(String file:Arrays.asList("alert_workflow_upgrade_20261013.sql","alert_workflow_preflight_20261013.sql","alert_workflow_postcheck_20261013.sql"))
            assertEquals(AlertWorkflowTestDatabase.read("src/main/resources/sql/"+file),AlertWorkflowTestDatabase.read(peer+"src/main/resources/sql/"+file),file);
        Map<String,String> fresh=triggers(init),migration=triggers(upgrade),other=triggers(AlertWorkflowTestDatabase.read(peer+"src/main/resources/sql/init.sql"));
        assertEquals(fresh,migration,"fresh init and upgrade must enforce exactly the same owned guards");
        assertEquals(fresh,other,"both editions must enforce exactly the same owned guards");
        int start=upgrade.indexOf("-- Read-only fail-closed preflight"),end=upgrade.indexOf("\n-- Baseline alert rows");
        assertTrue(start>=0&&end>start);assertEquals(preflight,upgrade.substring(start,end),"embedded preflight must be byte-equivalent");
    }
    boolean rejects(JdbcTemplate jdbc,String name,Map<String,Object> old,Map<String,Object> next) {
        String body=triggers(upgrade).get(name);assertNotNull(body,name+" owned guard is required");
        Matcher condition=Pattern.compile("(?s)^BEGIN\\s+IF (.*?) THEN\\s+SIGNAL SQLSTATE '45000'.*END IF;\\s+END$").matcher(body);
        if(!condition.matches()){
            assertTrue(body.matches("(?s)^BEGIN\\s+SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='[^']*';\\s+END$"),"Only a recognized unconditional SIGNAL is a predicate-free guard");return true;
        }
        String expression=condition.group(1);Matcher fields=Pattern.compile("\\b(OLD|NEW)\\.([a-z_]+)").matcher(expression);StringBuffer bound=new StringBuffer();
        while(fields.find()){Map<String,Object> row="OLD".equals(fields.group(1))?old:next;assertTrue(row.containsKey(fields.group(2)),"complete static row fixture required: "+fields.group());fields.appendReplacement(bound,Matcher.quoteReplacement(literal(row.get(fields.group(2)))));}fields.appendTail(bound);
        Boolean rejected=jdbc.queryForObject("SELECT ("+bound+")",Boolean.class);return Boolean.TRUE.equals(rejected);
    }
    /** Narrow documented MySQL 5.7 PAD SPACE model for the ASCII DUE comparison operands only.
     * H2 is not a native collation oracle. Uncast operands trim trailing ASCII spaces before
     * comparison; CAST(... AS BINARY) operands retain every byte and bypass this rewrite. */
    static String mysql57DuePadSpace(String expression) {
        String json="JSON_UNQUOTE\\(JSON_EXTRACT\\((?:NEW|e)\\.payload_json,'\\$\\.due(?:Key|At)'\\)\\)";
        return expression.replaceAll("((?:COALESCE\\("+json+",''\\)|"+json+"))=","RTRIM($1)=");
    }
    boolean rejectsMysql57DuePadSpace(JdbcTemplate jdbc,Map<String,Object> next) {
        String original=triggers(upgrade).get("aw57_event_bi");
        String modeled=mysql57DuePadSpace(original);
        Matcher fields=Pattern.compile("\\bNEW\\.([a-z_]+)").matcher(modeled);StringBuffer bound=new StringBuffer();
        while(fields.find()){assertTrue(next.containsKey(fields.group(1)));fields.appendReplacement(bound,Matcher.quoteReplacement(literal(next.get(fields.group(1)))));}fields.appendTail(bound);
        Matcher condition=Pattern.compile("(?s)^BEGIN\\s+IF (.*?) THEN\\s+SIGNAL.*END$").matcher(bound);
        assertTrue(condition.matches());return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ("+condition.group(1)+")",Boolean.class));
    }
    int diagnostics(JdbcTemplate jdbc,String label) {
        Matcher query=Pattern.compile("(?m)^SELECT '"+Pattern.quote(label)+"' AS check_name,[^;]+;").matcher(postcheck);
        assertTrue(query.find(),label+" diagnostic is required");return((Number)jdbc.queryForMap(query.group()).get("BLOCKERS")).intValue();
    }
    boolean preflightAllows(JdbcTemplate jdbc,String label) {
        Matcher check=Pattern.compile("(?s)SET @aw57_ok=\\((.*?)\\);\\nSET @aw57_sql=IF\\(@aw57_ok,'SELECT 1 AS ([a-z_]+)'[^;]+;").matcher(preflight);String condition=null;
        while(check.find())if(label.equals(check.group(2))){condition=check.group(1);break;}
        assertNotNull(condition,label+" fail-closed preflight check is required");
        condition=condition.replace("information_schema.columns","aw57_meta_columns").replace("information_schema.statistics","aw57_meta_statistics").replace("DATABASE()","'synthetic'");
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ("+condition+")",Boolean.class));
    }
    private static Map<String,String> triggers(String sql) {
        Map<String,String> result=new LinkedHashMap<>();Matcher trigger=Pattern.compile("(?s)CREATE TRIGGER `([^`]+)` BEFORE (?:INSERT|UPDATE|DELETE) ON `[^`]+` FOR EACH ROW\\s+(BEGIN.*?END)\\$\\$").matcher(sql);
        while(trigger.find()){assertNull(result.put(trigger.group(1),trigger.group(2)),"duplicate owned guard");assertTrue(trigger.group(1).length()<=64);}return result;
    }
    private static String literal(Object value){if(value==null)return "NULL";if(value instanceof Number)return value.toString();if(value instanceof Boolean)return(Boolean)value?"1":"0";return "'"+value.toString().replace("'","''")+"'";}
}
