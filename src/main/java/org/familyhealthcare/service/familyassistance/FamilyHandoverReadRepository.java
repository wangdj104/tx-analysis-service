package org.familyhealthcare.service.familyassistance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Clob;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import static org.familyhealthcare.service.workflow.WorkflowData.utc;
import java.util.*;

/** 只读普通交接来源；长度判断与正文读取必须在同一条 SQL 中，不能先读整段再截断。 */
@Repository
public class FamilyHandoverReadRepository {
    private static final Set<String> B_UTC_COLUMNS=new HashSet<>(Arrays.asList(
            "a_accepted_at","a_handled_at","a_confirmed_at","a_deadline_at",
            "c_accepted_at","c_handled_at","c_confirmed_at","c_deadline_at"));
    private final JdbcTemplate jdbc;
    public FamilyHandoverReadRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Map<String,Object> bySource(long patient,long source,boolean content) {
        return source(patient,source,content,content);
    }
    public Map<String,Object> byAssistance(long patient,long assistance,boolean content) {
        return assistance(patient,assistance,content,content);
    }
    /** 人选只需要动作身份与证据，不加载来源标题或说明。 */
    public Map<String,Object> candidateContext(long patient,long target,boolean requested,boolean evidence) {
        return requested?assistance(patient,target,false,evidence):source(patient,target,false,evidence);
    }
    private Map<String,Object> source(long patient,long source,boolean body,boolean evidence) {
        return one("SELECT "+sourceColumns(body,null)+","+assistanceColumns("c",evidence,null)
                +" FROM care_item s JOIN patient p ON p.id=s.patient_id LEFT JOIN family_assistance c ON c.id=s.family_assistance_id"
                +" WHERE s.patient_id=? AND s.id=?",patient,source);
    }
    private Map<String,Object> assistance(long patient,long assistance,boolean body,boolean evidence) {
        // 历史请求不读取当前标题或说明；当前指针仍须核验同患者、同来源及证据完整性。
        return one("SELECT "+sourceColumns(body,"s.family_workflow_version=1 AND s.family_assistance_id=a.id AND "+identity("a"))
                +","+assistanceColumns("a",evidence,null)+","+assistanceColumns("c",evidence,"c.id<>a.id")
                +" FROM family_assistance a JOIN care_item s ON s.id=a.care_item_id JOIN patient p ON p.id=s.patient_id"
                +" LEFT JOIN family_assistance c ON c.id=s.family_assistance_id"
                +" WHERE a.patient_id=? AND a.id=?",patient,assistance);
    }
    public List<Map<String,Object>> work(long patient,long after,int limit) {
        page(limit);
        // 直接按当前指针和活跃状态取页；不把历史全量读出后再在 Java 里筛选。
        return rows("SELECT "+sourceColumns(true,null)+","+assistanceColumns("c",true,null)
                +" FROM family_assistance c JOIN care_item s ON s.id=c.care_item_id AND s.family_assistance_id=c.id"
                +" JOIN patient p ON p.id=s.patient_id WHERE c.patient_id=? AND "+identity("c")
                +" AND s.family_workflow_version=1 AND c.state IN ('REQUESTED','ACCEPTED','AWAITING_CONFIRMATION')"
                +" AND c.id>? ORDER BY c.id LIMIT ?",patient,after,limit+1);
    }
    public List<Map<String,Object>> workContexts(long patient,Collection<Long> assistanceIds) {
        SortedSet<Long> ids=subjects(assistanceIds,25);
        if(ids.isEmpty())return Collections.emptyList();
        List<Object> args=new ArrayList<>();args.add(patient);args.addAll(ids);
        // 同一请求即使已终结或指针变化也取回，以便整页复核，而不是悄悄丢掉发生变化的行。
        return rows("SELECT "+sourceColumns(false,null)+","+assistanceColumns("c",false,null)
                +" FROM family_assistance c JOIN care_item s ON s.id=c.care_item_id JOIN patient p ON p.id=s.patient_id"
                +" WHERE c.patient_id=? AND c.id IN ("+placeholders(ids.size())+") ORDER BY c.id",args.toArray());
    }
    public Set<Long> candidateIds(long patient,long owner,String search) {
        String name=FamilyHandoverProjection.search(search),clause=name==null?"":" AND LOCATE(?,u.real_name)>0";
        Set<Long> ids=new TreeSet<>();
        List<Object> ownerArgs=new ArrayList<>();ownerArgs.add(owner);if(name!=null)ownerArgs.add(name);
        for(Map<String,Object> row:rows("SELECT u.id AS subject_id FROM sys_user u WHERE u.id=?"+clause+" LIMIT 1",ownerArgs.toArray()))
            ids.add(subject(row.get("subject_id")));
        String[][] sources={{"care_access_grant","grantee_user_id",""},{"doctor_patient_assignment","doctor_user_id"," AND x.status='ACTIVE'"},{"care_member","user_id",""}};
        for(String[] source:sources) {
            List<Object> args=new ArrayList<>();args.add(patient);if(name!=null)args.add(name);
            // DISTINCT 必须先于 LIMIT；同一关系源的重复事实不能挤掉另一位关联账户。
            List<Map<String,Object>> found=rows("SELECT DISTINCT x."+source[1]+" AS subject_id FROM "+source[0]
                    +" x JOIN sys_user u ON u.id=x."+source[1]+" WHERE x.patient_id=?"+source[2]+clause+" ORDER BY x."+source[1]+" LIMIT 41",args.toArray());
            if(found.size()>40)throw FamilyHandoverProjection.candidatesOverflow();
            for(Map<String,Object> row:found)ids.add(subject(row.get("subject_id")));
            if(ids.size()>40)throw FamilyHandoverProjection.candidatesOverflow();
        }
        return Collections.unmodifiableSet(ids);
    }
    public Map<Long,String> names(Collection<Long> subjects) {
        SortedSet<Long> ids=subjects(subjects,75);Map<Long,String> names=new LinkedHashMap<>();
        if(ids.isEmpty())return names;
        for(Map<String,Object> row:rows("SELECT id AS subject_id,LEFT(real_name,50) AS display_name FROM sys_user WHERE id IN ("
                +placeholders(ids.size())+") ORDER BY id LIMIT 76",ids.toArray())) {
            Object raw=row.get("display_name");String name=raw instanceof String?(String)raw:null;
            if(!FamilyHandoverProjection.text(name,50,200)||name!=null&&name.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)))name=null;
            names.put(subject(row.get("subject_id")),name);
        }
        return names;
    }
    static void page(int limit){if(limit<1||limit>25)throw FamilyHandoverProjection.invalidRequest();}
    private static SortedSet<Long> subjects(Collection<Long> subjects,int maximum) {
        if(subjects==null||subjects.size()>maximum)throw FamilyHandoverProjection.unavailable();
        SortedSet<Long> ids=new TreeSet<>();for(Long id:subjects)ids.add(subject(id));return ids;
    }
    private static long subject(Object value) {
        if(!(value instanceof Number)||((Number)value).longValue()<1||((Number)value).longValue()>9007199254740991L)throw FamilyHandoverProjection.unavailable();
        return ((Number)value).longValue();
    }
    private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static String sourceColumns(boolean content,String eligible) {
        String columns="s.id AS s_id,s.patient_id AS s_patient_id,s.kind AS s_kind,"
                +"s.family_workflow_version AS s_marker,s.family_assistance_id AS s_pointer,p.user_id AS owner_id";
        if(!content)return columns;
        String current="s.family_workflow_version=1 AND s.family_assistance_id=c.id AND "+identity("c");
        String condition="s.kind='HANDOVER' AND ((s.family_workflow_version=0 AND s.family_assistance_id IS NULL) OR ("+current+")) AND "
                +(eligible==null?"":"("+eligible+") AND ");
        return columns+",OCTET_LENGTH(s.title) AS s_title_bytes,"
                +"CASE WHEN "+condition+"OCTET_LENGTH(s.title)<=800 THEN s.title ELSE NULL END AS s_title,"
                +"OCTET_LENGTH(s.data_json) AS s_data_bytes,"
                +"CASE WHEN "+condition+"OCTET_LENGTH(s.data_json)<=65536 THEN s.data_json ELSE NULL END AS s_data_json";
    }
    private static String assistanceColumns(String alias,boolean content,String eligible) {
        List<String> columns=new ArrayList<>();
        for(String name:Arrays.asList("id","patient_id","source_kind","care_item_id","care_appointment_id",
                "request_kind","lock_version","cycle","state","originator_id","assigned_user_id","accepted_by","handled_by",
                "accepted_at","handled_at","confirmed_at","deadline_at","origin_evidence_hash"))
            columns.add(alias+"."+name+" AS "+alias+"_"+name);
        columns.add("OCTET_LENGTH("+alias+".origin_evidence_json) AS "+alias+"_evidence_bytes");
        if(content)columns.add("CASE WHEN "+identity(alias)+" AND "+(eligible==null?"":"("+eligible+") AND ")
                +"OCTET_LENGTH("+alias+".origin_evidence_json)<=16384 THEN "+alias+".origin_evidence_json ELSE NULL END AS "+alias+"_evidence_json");
        return String.join(",",columns);
    }
    private static String identity(String alias) {
        return "s.kind='HANDOVER' AND "+alias+".patient_id=s.patient_id AND "+alias+".source_kind='CARE_ITEM' AND "
                +alias+".care_item_id=s.id AND "+alias+".care_appointment_id IS NULL AND "+alias+".request_kind='HANDOVER'";
    }
    private Map<String,Object> one(String sql,Object...args) {
        List<Map<String,Object>> rows=rows(sql,args);return rows.isEmpty()?null:rows.get(0);
    }
    private List<Map<String,Object>> rows(String sql,Object...args) {
        return jdbc.query(sql,ps->{ps.setQueryTimeout(5);FamilyAssistanceRepository.bind(ps,args);},(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();ResultSetMetaData metadata=rs.getMetaData();
            for(int column=1;column<=metadata.getColumnCount();column++) {
                Object value=rs.getObject(column);if(value instanceof Clob)value=rs.getString(column);
                String name=metadata.getColumnLabel(column).toLowerCase(Locale.ROOT);
                // B 时间按原仓库的 UTC 规则读取；不能由 JVM 时区改写已接受、处理或截止事实。
                // 只转换这次明确选出的 B 字段，不给来源的无时区 DATETIME 推断时区。
                if(value instanceof Timestamp&&B_UTC_COLUMNS.contains(name))value=rs.getTimestamp(column,utc()).toInstant();
                row.put(name,value);
            }
            return row;
        });
    }
}
