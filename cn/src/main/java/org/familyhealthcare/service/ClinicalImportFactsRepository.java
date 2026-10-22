package org.familyhealthcare.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;
import java.util.*;
import static org.familyhealthcare.service.ClinicalImportFacts.*;

/** 有界元数据就绪检查与按原生主键读取；普通列表不调用此仓储。 */
@Repository
public class ClinicalImportFactsRepository {
    @Autowired private JdbcTemplate jdbc;
    /** Identity only; this never acquires a connection or changes the importer contract. */
    public void requireDataSource(javax.sql.DataSource expected) {
        if(expected==null||jdbc==null||jdbc.getDataSource()!=expected)throw Failure.unavailable();
    }
    private static final String ENVIRONMENT = "SELECT DATABASE() AS schema_name, VERSION() AS server_version, @@character_set_client AS client_charset, @@character_set_connection AS connection_charset, @@character_set_results AS results_charset";
    private static final String TABLES = "SELECT TABLE_NAME AS table_name, ENGINE AS engine, TABLE_COLLATION AS table_collation FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record') LIMIT 3";
    private static final String COLUMNS = "SELECT TABLE_NAME AS table_name, COLUMN_NAME AS column_name, DATA_TYPE AS data_type, COLUMN_TYPE AS column_type, IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default, CHARACTER_MAXIMUM_LENGTH AS character_maximum_length, CHARACTER_SET_NAME AS character_set_name FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME IN ('import_facts_json','record_time')) OR (TABLE_NAME='medical_record' AND COLUMN_NAME='import_facts_json')) LIMIT 4";
    private static final String INDEXES = "SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name, COLUMN_NAME AS column_name, SEQ_IN_INDEX AS seq_in_index, NON_UNIQUE AS non_unique, SUB_PART AS sub_part, INDEX_TYPE AS index_type, COLLATION AS collation FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME IN ('PRIMARY','uk_bp_external','idx_bp_patient_date')) OR (TABLE_NAME='medical_record' AND INDEX_NAME IN ('PRIMARY','uk_record_external'))) LIMIT 20";
    private static final String TRIGGER_QUERY = "SELECT TRIGGER_NAME AS trigger_name, EVENT_OBJECT_TABLE AS table_name, EVENT_MANIPULATION AS event_manipulation, ACTION_TIMING AS action_timing, ACTION_ORIENTATION AS action_orientation, ACTION_CONDITION AS action_condition, ACTION_STATEMENT AS action_statement, CHARACTER_SET_CLIENT AS character_set_client, COLLATION_CONNECTION AS collation_connection, DATABASE_COLLATION AS database_collation FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME IN ('if57_bp_insert_facts','if57_bp_update_facts','if57_medical_insert_facts','if57_medical_update_facts') LIMIT 5";
    private static final Map<String,Trigger> TRIGGERS = new LinkedHashMap<>();
    static {
        TRIGGERS.put("if57_bp_insert_facts",new Trigger("bp_self_monitor_record","INSERT","BEGIN\n  IF NEW.import_facts_json IS NOT NULL THEN\n    IF OCTET_LENGTH(NEW.import_facts_json) > 4096 OR JSON_VALID(NEW.import_facts_json) <> 1 THEN\n      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_INVALID_IMPORT_FACTS';\n    END IF;\n    IF JSON_TYPE(NEW.import_facts_json) <> 'OBJECT'\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.version')) <=> 'INTEGER')\n      OR NOT (JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.version')) <=> '1')\n      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.format')) <=> BINARY NEW.source_type)\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.timestamps')) <=> 'OBJECT')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.basis')) <=> 'OBJECT')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.readings')) <=> 'ARRAY')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.selectedTimestamp')) <=> 'STRING')\n      OR NOT (JSON_CONTAINS_PATH(NEW.import_facts_json, 'all', '$.version', '$.format', '$.kind', '$.timestamps', '$.selectedTimestamp', '$.basis', '$.readings') <=> 1)\n      OR NEW.source_external_id IS NULL OR CHAR_LENGTH(TRIM(NEW.source_external_id)) = 0\n      OR NOT (BINARY NEW.source_type <=> BINARY 'FHIR') AND NOT (BINARY NEW.source_type <=> BINARY 'DEVICE')\n      OR NOT (JSON_LENGTH(NEW.import_facts_json) <=> IF(BINARY NEW.source_type = BINARY 'DEVICE', 8, 7))\n      OR (BINARY NEW.source_type = BINARY 'DEVICE' AND NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.deviceUnit')) <=> 'OBJECT'))\n      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.kind')) <=> BINARY NEW.measure_type)\n      OR NOT (BINARY NEW.measure_type <=> BINARY 'BP') AND NOT (BINARY NEW.measure_type <=> BINARY 'BG') AND NOT (BINARY NEW.measure_type <=> BINARY 'BP_BG')\n      OR NOT (JSON_LENGTH(JSON_EXTRACT(NEW.import_facts_json, '$.readings')) <=> IF(BINARY NEW.measure_type = BINARY 'BP', 2, IF(BINARY NEW.measure_type = BINARY 'BG', 1, 3))) THEN\n      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_INVALID_IMPORT_FACTS';\n    END IF;\n  END IF;\nEND"));
        TRIGGERS.put("if57_bp_update_facts",new Trigger("bp_self_monitor_record","UPDATE","BEGIN\n  IF NOT ((BINARY NEW.import_facts_json <=> BINARY OLD.import_facts_json)\n      AND (OCTET_LENGTH(NEW.import_facts_json) <=> OCTET_LENGTH(OLD.import_facts_json))) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_ORIGINAL_BINDING_IMMUTABLE';\n  END IF;\n  IF OLD.import_facts_json IS NOT NULL AND (\n      NOT (NEW.id <=> OLD.id) OR NOT (NEW.patient_id <=> OLD.patient_id) OR NOT (NEW.user_id <=> OLD.user_id)\n      OR NOT ((BINARY NEW.source_type <=> BINARY OLD.source_type) AND (OCTET_LENGTH(NEW.source_type) <=> OCTET_LENGTH(OLD.source_type)))\n      OR NOT ((BINARY NEW.source_external_id <=> BINARY OLD.source_external_id) AND (OCTET_LENGTH(NEW.source_external_id) <=> OCTET_LENGTH(OLD.source_external_id)))) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_ORIGINAL_BINDING_IMMUTABLE';\n  END IF;\nEND"));
        TRIGGERS.put("if57_medical_insert_facts",new Trigger("medical_record","INSERT","BEGIN\n  IF NEW.import_facts_json IS NOT NULL THEN\n    IF OCTET_LENGTH(NEW.import_facts_json) > 4096 OR JSON_VALID(NEW.import_facts_json) <> 1 THEN\n      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_INVALID_IMPORT_FACTS';\n    END IF;\n    IF JSON_TYPE(NEW.import_facts_json) <> 'OBJECT'\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.version')) <=> 'INTEGER')\n      OR NOT (JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.version')) <=> '1')\n      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.format')) <=> BINARY NEW.source_type)\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.timestamps')) <=> 'OBJECT')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.basis')) <=> 'OBJECT')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.readings')) <=> 'ARRAY')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.selectedTimestamp')) <=> 'STRING')\n      OR NOT (JSON_CONTAINS_PATH(NEW.import_facts_json, 'all', '$.version', '$.format', '$.kind', '$.timestamps', '$.selectedTimestamp', '$.basis', '$.readings') <=> 1)\n      OR NEW.source_external_id IS NULL OR CHAR_LENGTH(TRIM(NEW.source_external_id)) = 0\n      OR NOT (BINARY NEW.source_type <=> BINARY 'FHIR')\n      OR NOT (JSON_LENGTH(NEW.import_facts_json) <=> 7)\n      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.kind')) <=> BINARY 'LAB')\n      OR NOT (JSON_LENGTH(JSON_EXTRACT(NEW.import_facts_json, '$.readings')) <=> 1) THEN\n      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_INVALID_IMPORT_FACTS';\n    END IF;\n  END IF;\nEND"));
        TRIGGERS.put("if57_medical_update_facts",new Trigger("medical_record","UPDATE","BEGIN\n  IF NOT ((BINARY NEW.import_facts_json <=> BINARY OLD.import_facts_json)\n      AND (OCTET_LENGTH(NEW.import_facts_json) <=> OCTET_LENGTH(OLD.import_facts_json))) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_ORIGINAL_BINDING_IMMUTABLE';\n  END IF;\n  IF OLD.import_facts_json IS NOT NULL AND (\n      NOT (NEW.id <=> OLD.id) OR NOT (NEW.patient_id <=> OLD.patient_id) OR NOT (NEW.user_id <=> OLD.user_id)\n      OR NOT ((BINARY NEW.source_type <=> BINARY OLD.source_type) AND (OCTET_LENGTH(NEW.source_type) <=> OCTET_LENGTH(OLD.source_type)))\n      OR NOT ((BINARY NEW.source_external_id <=> BINARY OLD.source_external_id) AND (OCTET_LENGTH(NEW.source_external_id) <=> OCTET_LENGTH(OLD.source_external_id)))) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_ORIGINAL_BINDING_IMMUTABLE';\n  END IF;\nEND"));
    }
    public void requireReady() {
        try {
            // 每次请求重新验证；每条语句独立 5 秒超时，最多五次只读元数据查询。
            List<Map<String,Object>> env=query(ENVIRONMENT),tables=query(TABLES),columns=query(COLUMNS),indexes=query(INDEXES),triggers=query(TRIGGER_QUERY);
            if(env.size()!=1||tables.size()!=2||columns.size()!=3||indexes.size()!=11||triggers.size()!=4)throw Failure.notReady();
            Map<String,Object> e=env.get(0);String version=string(e,"server_version");
            if(string(e,"schema_name").isEmpty()||!(version.equals("5.7.18")||version.startsWith("5.7.18-"))||version.toLowerCase(Locale.ROOT).contains("mariadb"))throw Failure.notReady();
            for(String name:Arrays.asList("client_charset","connection_charset","results_charset"))if(!"utf8mb4".equals(string(e,name)))throw Failure.notReady();
            Set<String> seen=new HashSet<>();
            for(Map<String,Object> table:tables) {
                String name=string(table,"table_name");
                if(!Arrays.asList("bp_self_monitor_record","medical_record").contains(name)||!seen.add(name)||!"InnoDB".equals(string(table,"engine"))||!string(table,"table_collation").startsWith("utf8mb4_"))throw Failure.notReady();
            }
            seen.clear();
            for(Map<String,Object> column:columns) {
                String table=string(column,"table_name"),name=string(column,"column_name");
                if(!seen.add(table+"."+name)||!"YES".equals(string(column,"is_nullable"))||column.get("column_default")!=null||!"utf8mb4".equals(string(column,"character_set_name")))throw Failure.notReady();
                if("record_time".equals(name)) {
                    if(!"bp_self_monitor_record".equals(table)||!"varchar".equals(string(column,"data_type"))||!"varchar(15)".equals(string(column,"column_type"))||number(column,"character_maximum_length")!=15)throw Failure.notReady();
                } else if(!"import_facts_json".equals(name)||!Arrays.asList("bp_self_monitor_record","medical_record").contains(table)||!"text".equals(string(column,"data_type"))||!"text".equals(string(column,"column_type")))throw Failure.notReady();
            }
            Set<String> expected=new HashSet<>();
            index(expected,"bp_self_monitor_record","PRIMARY",0,"id");index(expected,"medical_record","PRIMARY",0,"id");
            index(expected,"bp_self_monitor_record","uk_bp_external",0,"patient_id","source_type","source_external_id");
            index(expected,"medical_record","uk_record_external",0,"patient_id","source_type","source_external_id");
            index(expected,"bp_self_monitor_record","idx_bp_patient_date",1,"patient_id","record_date","record_time");
            for(Map<String,Object> i:indexes)if(!expected.remove(string(i,"table_name")+"|"+string(i,"index_name")+"|"+number(i,"non_unique")+"|"+number(i,"seq_in_index")+"|"+string(i,"column_name"))||i.get("sub_part")!=null||!"BTREE".equals(string(i,"index_type"))||!"A".equals(string(i,"collation")))throw Failure.notReady();
            if(!expected.isEmpty())throw Failure.notReady();
            seen.clear();
            for(Map<String,Object> row:triggers) {
                String name=string(row,"trigger_name");Trigger expectedTrigger=TRIGGERS.get(name);
                if(expectedTrigger==null||!seen.add(name)||!expectedTrigger.table.equals(string(row,"table_name"))||!expectedTrigger.event.equals(string(row,"event_manipulation"))
                        ||!"BEFORE".equals(string(row,"action_timing"))||!"ROW".equals(string(row,"action_orientation"))||row.get("action_condition")!=null
                        ||!"utf8mb4".equals(string(row,"character_set_client"))||!string(row,"collation_connection").startsWith("utf8mb4_")||!string(row,"database_collation").startsWith("utf8mb4_")
                        ||!normalizeSql(expectedTrigger.body).equals(normalizeSql(string(row,"action_statement"))))throw Failure.notReady();
            }
        } catch(RuntimeException invalid){throw Failure.notReady();}
    }
    public Map<String,Object> readBpOriginal(long patientId,long recordId){return readOriginal("bp_self_monitor_record",patientId,recordId);}
    public Map<String,Object> readMedicalOriginal(Long patientId,long recordId){return readOriginal("medical_record",patientId,recordId);}
    private Map<String,Object> readOriginal(String table,Long patientId,long recordId) {
        try {
            requireReady();
            List<Map<String,Object>> rows=query("SELECT import_facts_json,source_type,source_external_id FROM "+table+" WHERE patient_id <=> ? AND id=? LIMIT 1",patientId,recordId);
            if(rows.size()!=1)throw Failure.unavailable();Map<String,Object> row=rows.get(0);
            if(row.get("import_facts_json")==null)return map("status","NOT_RETAINED");
            if(!(row.get("import_facts_json") instanceof String)||string(row,"source_external_id").trim().isEmpty())throw Failure.unavailable();
            ClinicalImportFacts facts=decode((String)row.get("import_facts_json"),string(row,"source_type"));
            if("medical_record".equals(table)!= "LAB".equals(facts.kind()))throw Failure.unavailable();
            return map("status","RETAINED","facts",facts.toView());
        } catch(RuntimeException unavailable){throw Failure.unavailable();}
    }
    private List<Map<String,Object>> query(String sql,Object... args) {
        return jdbc.query(connection->{PreparedStatement statement=connection.prepareStatement(sql);statement.setQueryTimeout(5);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},new ColumnMapRowMapper());
    }
    private static void index(Set<String> out,String table,String name,int nonUnique,String... columns){for(int i=0;i<columns.length;i++)out.add(table+"|"+name+"|"+nonUnique+"|"+(i+1)+"|"+columns[i]);}
    private static String string(Map<String,Object> row,String field){Object value=row.get(field);return value instanceof String?(String)value:"";}
    private static long number(Map<String,Object> row,String field){Object value=row.get(field);return value instanceof Number?((Number)value).longValue():Long.MIN_VALUE;}
    // 只忽略 SQL 关键字大小写、外部空白与标识符反引号；字符串令牌逐字节比较。
    private static String normalizeSql(String sql) {
        StringBuilder out=new StringBuilder();boolean quoted=false;
        for(int i=0;i<sql.length();i++) {char c=sql.charAt(i);if(c=='\''){out.append(c);if(quoted&&i+1<sql.length()&&sql.charAt(i+1)=='\''){out.append(sql.charAt(++i));continue;}quoted=!quoted;}else if(quoted)out.append(c);else if(!Character.isWhitespace(c)&&c!='`')out.append(Character.toLowerCase(c));}
        return out.toString();
    }
    private static final class Trigger {final String table,event,body;Trigger(String table,String event,String body){this.table=table;this.event=event;this.body=body;}}
}
