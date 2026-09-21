package org.familyhealthcare.service;

import org.familyhealthcare.util.DataScopeHelper;
import com.alibaba.fastjson2.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

@Service
public class FamilyBackupService {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataScopeHelper scope;
    @Autowired private CareMembershipService membership;
    // Parent rows precede their dependent rows. System accounts and passwords are deliberately outside family data.
    static final List<String> TABLES=Arrays.asList("patient","patient_clinical","patient_health_target","medication","dialysis_record","medical_record","medical_record_item","medical_record_attachment","dry_weight_monthly","bp_self_monitor_record","bp_pattern_analysis","nutrition_diary","nutrition_assessment","complication_record","ai_analysis_record","health_analysis_automation","alert_rule","alert_record","medication_reminder","medication_log","medication_intake","dialysis_schedule","health_event","care_item","medication_stock","medication_stock_movement","care_intake_action");

    @Transactional(readOnly=true) public byte[] exportArchive() throws IOException {
        List<Long> pids=membership.accessiblePatients(scope.requireUserId());
        JSONObject root=new JSONObject();root.put("format","family-health-backup");root.put("version",1);root.put("createdAt",LocalDateTime.now().toString());
        JSONObject tables=new JSONObject();root.put("tables",tables);List<String>warnings=new ArrayList<>();root.put("warnings",warnings);
        Set<String>present=presentTables();List<Object> recordIds=new ArrayList<>();
        for(String table:TABLES){if(!present.contains(table))continue;List<Map<String,Object>>rows;
            if(table.equals("medical_record_item")||table.equals("medical_record_attachment"))rows=selectIn(table,"record_id",recordIds);
            else rows=selectIn(table,table.equals("patient")?"id":"patient_id",new ArrayList<Object>(pids));
            if(table.equals("medical_record"))for(Map<String,Object>r:rows)recordIds.add(r.get("id"));
            for(Map<String,Object>r:rows){
                for(Map.Entry<String,Object>cell:r.entrySet()){Object v=cell.getValue();if(v instanceof Timestamp)cell.setValue(((Timestamp)v).toLocalDateTime().toString().replace('T',' '));else if(v instanceof java.sql.Date)cell.setValue(v.toString());else if(v instanceof java.sql.Clob){try{java.sql.Clob text=(java.sql.Clob)v;cell.setValue(text.getSubString(1,(int)text.length()));}catch(java.sql.SQLException e){throw new IOException("NonemethodreadAttachmentcontent",e);}}}
                if(table.equals("medical_record_attachment")&&(r.get("file_content")==null||r.get("file_content").toString().isEmpty())){
                    String path=String.valueOf(r.get("file_path"));
                    if(!path.startsWith("inline://")&&!path.equals("null")&&!path.startsWith("http")){
                        java.nio.file.Path file=java.nio.file.Paths.get(path);
                        if(java.nio.file.Files.isRegularFile(file))r.put("file_content",Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(file)));
                        else warnings.add("Attachmentoriginalfiledoes not exist: "+r.get("file_name"));
                    }else warnings.add("Attachmentmissingoriginalfilecontent: "+r.get("file_name"));
                }
            }tables.put(table,rows);
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("family-health.json"));zip.write(root.toJSONString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}return out.toByteArray();
    }
    private Set<String> presentTables(){return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Set<String>>)connection->{Set<String>names=new HashSet<>();try(java.sql.ResultSet rows=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})){while(rows.next()){String name=rows.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT);if(TABLES.contains(name))names.add(name);}}return names;});}
    private List<Map<String,Object>>selectIn(String table,String column,List<Object>ids){if(ids.isEmpty())return new ArrayList<>();return jdbc.queryForList("SELECT * FROM `"+table+"` WHERE `"+column+"` IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id",ids.toArray());}

    public JSONObject readArchive(byte[] bytes) throws IOException {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(!"family-health.json".equals(entry.getName()))continue;
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[]buffer=new byte[8192];int n;while((n=zip.read(buffer))!=-1){if(out.size()+n>200*1024*1024)throw new IllegalArgumentException("decompressafter  backupcopyexceed200MB, Please by Family Membersplitbackupcopy");out.write(buffer,0,n);}
            JSONObject root=JSON.parseObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            if(!"family-health-backup".equals(root.getString("format"))||root.getIntValue("version")!=1||root.getJSONObject("tables")==null)throw new IllegalArgumentException("backupcopyformat or versionthis not receivesupport");
            if(root.getJSONObject("tables").getJSONArray("patient")==null)throw new IllegalArgumentException("backupcopymissingFamily Memberrecord");return root;
        }}throw new IllegalArgumentException("not Yeshas valid familyhealthbackupcopyfile");
    }
    public Map<String,Object> preview(byte[]bytes)throws IOException {JSONObject root=readArchive(bytes);Map<String,Object>out=new LinkedHashMap<>();Map<String,Integer>counts=new LinkedHashMap<>();for(String table:TABLES){JSONArray rows=root.getJSONObject("tables").getJSONArray(table);if(rows!=null)counts.put(table,rows.size());}out.put("createdAt",root.get("createdAt"));out.put("counts",counts);out.put("warnings",root.get("warnings"));out.put("patients",root.getJSONObject("tables").getJSONArray("patient"));return out;}

    @Transactional public Map<String,Object> restore(byte[]bytes)throws IOException {
        Long uid=scope.requireUserId();JSONObject root=readArchive(bytes),tables=root.getJSONObject("tables");Set<String>present=presentTables();
        for(String table:TABLES)if(tables.getJSONArray(table)!=null&&!tables.getJSONArray(table).isEmpty()&&!present.contains(table))throw new IllegalArgumentException("datadatabasemissingtable "+table+", Please first increaseleveldatadatabaseafter restore");
        Map<String,Map<String,Long>>ids=new HashMap<>();Map<String,Integer>counts=new LinkedHashMap<>();
        for(String table:TABLES){JSONArray rows=tables.getJSONArray(table);if(rows==null)continue;Map<String,Long>mapping=new HashMap<>();ids.put(table,mapping);int count=0;
            for(Object obj:rows){JSONObject raw=(JSONObject)obj;Map<String,Object>row=new LinkedHashMap<>(raw);Object oldId=row.remove("id");
                if(oldId==null||mapping.containsKey(oldId.toString()))throw new IllegalArgumentException("backupcopyincludeduplicate or missingID: "+table);
                if(!Arrays.asList("patient","medical_record_item","medical_record_attachment").contains(table))remap(row,"patient_id","patient",ids,true);
                remap(row,"medication_id","medication",ids,false);remap(row,"record_id","medical_record",ids,false);
                remap(row,"related_dialysis_id","dialysis_record",ids,false);remap(row,"dialysis_record_id","dialysis_record",ids,false);remap(row,"completed_record_id","dialysis_record",ids,false);
                remap(row,"reminder_id","medication_reminder",ids,false);remap(row,"intake_id","medication_intake",ids,false);
                remap(row,"rule_id","alert_rule",ids,false);
                for(String userColumn:Arrays.asList("user_id","actor_id","assigned_user_id"))if(row.containsKey(userColumn)&&row.get(userColumn)!=null)row.put(userColumn,uid);
                if(table.equals("patient")){row.put("user_id",uid);row.put("name",String.valueOf(row.get("name"))+" (restoresecondarythis ) ");row.put("deleted",0);}
                if(table.equals("medication_reminder"))row.put("enabled",0);
                if(table.equals("medication_intake")&&Arrays.asList("PENDING","SNOOZED","MISSED").contains(row.get("status")))row.put("status","CANCELLED");
                if(table.equals("care_item")){
                    if("ORDER".equals(row.get("kind"))&&Arrays.asList("ACTIVE","SCHEDULED").contains(row.get("status")))row.put("status","STOPPED");
                    row.put("notify_at",null);row.put("notified_at",null);row.put("escalated_at",null);
                }
                if(table.equals("medical_record_attachment")&&row.get("file_content")!=null)row.put("file_path","inline://"+row.get("record_id")+"/"+row.get("file_name"));
                if(table.equals("medication_stock_movement")&&row.get("source_key")!=null){String key=row.get("source_key").toString();String[]parts=key.split(":",2);if(parts.length==2){String target=parts[0].equals("INTAKE")?"medication_intake":"medication_log";Long mapped=ids.getOrDefault(target,Collections.emptyMap()).get(parts[1]);row.put("source_key",mapped==null?null:parts[0]+":"+mapped);}else row.put("source_key",null);}
                Number generated=new SimpleJdbcInsert(jdbc).withTableName(table).usingColumns(row.keySet().toArray(new String[0])).usingGeneratedKeyColumns("id").executeAndReturnKey(row);
                mapping.put(oldId.toString(),generated.longValue());count++;
            }counts.put(table,count);
        }
        // JSON links may point to later rows, so rewrite them after all identities are available.
        JSONArray careRows=tables.getJSONArray("care_item");if(careRows!=null)for(Object obj:careRows){JSONObject raw=(JSONObject)obj;String json=raw.getString("data_json");if(json==null)continue;JSONObject d=JSON.parseObject(json);
            remapJson(d,"medicationId","medication",ids);remapJson(d,"reportId","medical_record",ids);remapJson(d,"appointmentId","care_item",ids);remapJson(d,"nextAppointmentId","care_item",ids);remapJson(d,"sourceId","care_item",ids);remapJson(d,"intakeId","medication_intake",ids);
            if(d.getJSONArray("reminderIds")!=null){List<Long>mapped=new ArrayList<>();for(Object id:d.getJSONArray("reminderIds")){Long n=ids.getOrDefault("medication_reminder",Collections.emptyMap()).get(id.toString());if(n!=null)mapped.add(n);}d.put("reminderIds",mapped);}
            if(d.containsKey("escalationUserId"))d.put("escalationUserId",null);
            jdbc.update("UPDATE care_item SET data_json=? WHERE id=?",d.toJSONString(),ids.get("care_item").get(raw.get("id").toString()));
        }
        Map<String,Object>out=new LinkedHashMap<>();out.put("counts",counts);out.put("patientIds",ids.getOrDefault("patient",Collections.emptyMap()).values());out.put("message","already restorefor independentsecondarythis . Remindertemporarilynot Enabled, Please verifyprescriptionafter againEnabled, andagaininvitePlease carecompletemember. ");return out;
    }
    private void remap(Map<String,Object>row,String column,String table,Map<String,Map<String,Long>>ids,boolean required){if(!row.containsKey(column)||row.get(column)==null){if(required)throw new IllegalArgumentException("backupcopymissingFamily Memberrelated");return;}Long mapped=ids.getOrDefault(table,Collections.emptyMap()).get(row.get(column).toString());if(mapped==null&&required)throw new IllegalArgumentException("backupcopyin Family Memberrelateddoes not exist");row.put(column,mapped);}
    private void remapJson(JSONObject d,String key,String table,Map<String,Map<String,Long>>ids){if(d.get(key)!=null)d.put(key,ids.getOrDefault(table,Collections.emptyMap()).get(d.get(key).toString()));}
}
