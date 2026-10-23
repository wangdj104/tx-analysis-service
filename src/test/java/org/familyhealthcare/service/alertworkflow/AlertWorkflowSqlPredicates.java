package org.familyhealthcare.service.alertworkflow;

import com.alibaba.fastjson2.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes extracted SQL guard predicates against synthetic H2 rows, not native MySQL triggers/DDL. */
public final class AlertWorkflowSqlPredicates {
    private final String init;
    AlertWorkflowSqlPredicates() throws Exception {
        init=AlertWorkflowTestDatabase.read("src/main/resources/sql/init.sql");
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
        Map<String,String> current=triggers(init),other=triggers(AlertWorkflowTestDatabase.read(peer+"src/main/resources/sql/init.sql"));
        assertEquals(22,current.size(),"All alert workflow guards must be installed by the single initialization entry point");
        assertEquals(current,other,"Both editions must enforce the same alert guards");
    }
    boolean rejects(JdbcTemplate jdbc,String name,Map<String,Object> old,Map<String,Object> next) {
        String body=triggers(init).get(name);assertNotNull(body,name+" owned guard is required");
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
        String original=triggers(init).get("aw57_event_bi");
        String modeled=mysql57DuePadSpace(original);
        Matcher fields=Pattern.compile("\\bNEW\\.([a-z_]+)").matcher(modeled);StringBuffer bound=new StringBuffer();
        while(fields.find()){assertTrue(next.containsKey(fields.group(1)));fields.appendReplacement(bound,Matcher.quoteReplacement(literal(next.get(fields.group(1)))));}fields.appendTail(bound);
        Matcher condition=Pattern.compile("(?s)^BEGIN\\s+IF (.*?) THEN\\s+SIGNAL.*END$").matcher(bound);
        assertTrue(condition.matches());return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ("+condition.group(1)+")",Boolean.class));
    }
    /** Independent data-invariant queries; production trigger predicates are extracted from init.sql above. */
    int diagnostics(JdbcTemplate jdbc,String label) {
        String query;
        switch(label) {
            case "orphan_alert_events": query="SELECT COUNT(*) AS blockers FROM alert_event e LEFT JOIN alert_record a ON a.id=e.alert_id WHERE (e.event_type IS NOT NULL AND (a.id IS NULL OR a.patient_id<>e.patient_id OR a.workflow_version<>1 OR a.alert_type IS NULL OR a.alert_type<>'MEASUREMENT_FOLLOW_UP')) OR ((a.workflow_version=1 OR a.alert_type='MEASUREMENT_FOLLOW_UP') AND (e.event_type IS NULL OR a.patient_id<>e.patient_id))"; break;
            case "invalid_source_links": query="SELECT COUNT(*) AS blockers FROM alert_source_link l LEFT JOIN alert_source_identity s ON s.id=l.source_identity_id LEFT JOIN alert_record a ON a.id=l.alert_id WHERE s.id IS NULL OR a.id IS NULL OR s.source_type<>l.source_type OR s.source_id<>l.source_id OR a.patient_id<>s.patient_id OR a.workflow_version<>1 OR a.alert_type IS NULL OR a.alert_type<>'MEASUREMENT_FOLLOW_UP' OR a.source_type IS NULL OR a.source_type<>l.source_type OR a.source_id IS NULL OR a.source_id<>l.source_id OR a.metric_key IS NULL OR a.metric_key<>l.metric_key"; break;
            case "reserved_marker_conflicts": query="SELECT COUNT(*) AS blockers FROM alert_record WHERE (alert_type='MEASUREMENT_FOLLOW_UP' AND workflow_version<>1) OR (workflow_version=1 AND (alert_type IS NULL OR alert_type<>'MEASUREMENT_FOLLOW_UP'))"; break;
            case "unknown_source_action_qualification": query="SELECT COUNT(*) AS blockers FROM alert_source_revision WHERE action_versions_json IS NULL OR JSON_VALID(action_versions_json)=0 OR JSON_TYPE(CASE WHEN JSON_VALID(action_versions_json)=1 THEN action_versions_json ELSE 'null' END)<>'OBJECT' OR (revision_no=1 AND action_versions_json<>'{}')"; break;
            case "source_revision_status_mismatch": query="SELECT COUNT(*) AS blockers FROM alert_source_identity s LEFT JOIN alert_source_revision r ON r.source_identity_id=s.id AND r.revision_no=s.current_revision WHERE r.id IS NULL OR r.source_status<>s.status"; break;
            case "invalid_source_revision_provenance": query="SELECT COUNT(*) AS blockers FROM alert_source_revision WHERE source_status NOT IN ('ACTIVE','INVALIDATED') OR (revision_no=1 AND source_status<>'ACTIVE') OR (revision_no>1 AND (correction_reason IS NULL OR CHAR_LENGTH(TRIM(correction_reason))=0)) OR CHAR_LENGTH(correction_reason)>1000"; break;
            default: throw new IllegalArgumentException("Unknown invariant: "+label);
        }
        return jdbc.queryForObject(query,Integer.class);
    }
    private static Map<String,String> triggers(String sql) {
        Map<String,String> result=new LinkedHashMap<>();Matcher trigger=Pattern.compile("(?s)CREATE TRIGGER `(aw57_[^`]+)` BEFORE (?:INSERT|UPDATE|DELETE) ON `[^`]+` FOR EACH ROW\\s+(BEGIN.*?END)\\$\\$").matcher(sql);
        while(trigger.find()){assertNull(result.put(trigger.group(1),trigger.group(2)),"duplicate owned guard");assertTrue(trigger.group(1).length()<=64);}return result;
    }
    private static String literal(Object value){if(value==null)return "NULL";if(value instanceof Number)return value.toString();if(value instanceof Boolean)return(Boolean)value?"1":"0";return "'"+value.toString().replace("'","''")+"'";}
}
