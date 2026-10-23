package org.familyhealthcare.service.commongoal;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static org.familyhealthcare.service.commongoal.CommonCareGoalContracts.*;

/** Bounded patient-local queries. Summaries never load event JSON or medical sources. */
@Repository
public class CommonCareGoalReadRepository {
    private final JdbcTemplate jdbc;
    public CommonCareGoalReadRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Goal> goals(long patient, String state, long before, int limit) {
        page(limit);
        String predicate="OPEN".equals(state)?"state IN ('PROPOSED','ACTIVE')":"state=?";
        List<Object> args=new ArrayList<>(); args.add(id(patient));
        if (!"OPEN".equals(state)) args.add(choice(state,State.class).name());
        if(before!=0)args.add(id(before)); args.add(limit+1);
        return CommonCareGoalRepository.query(jdbc,"SELECT "+CommonCareGoalRepository.GOAL_COLUMNS
                +" FROM common_care_goal WHERE patient_id=? AND "+predicate+(before==0?"":" AND id<?")+" ORDER BY id DESC LIMIT ?",
                CommonCareGoalRepository::goal,args.toArray());
    }
    public List<Map<String,Object>> history(long patient, long goal, long before, int limit) {
        page(limit);
        List<Object> args=new ArrayList<>(Arrays.asList(id(goal),id(patient)));
        if(before!=0)args.add(id(before));args.add(limit+1);
        return CommonCareGoalRepository.query(jdbc,"SELECT patient_id,goal_id,sequence,cycle,operation,actor_id,recorded_at "
                        +"FROM common_care_goal_event WHERE goal_id=? AND patient_id=?"+(before==0?"":" AND sequence<?")+" ORDER BY sequence DESC LIMIT ?",
                (r,n)->WorkflowData.map("patientId",id(r.getLong("patient_id")),"goalId",id(r.getLong("goal_id")),
                        "sequence",id(r.getLong("sequence")),"cycle",id(r.getLong("cycle")),"operation",choice(r.getString("operation"),Operation.class).name(),
                        "actorId",id(r.getLong("actor_id")),"recordedAt",WorkflowData.time(r,"recorded_at")),args.toArray());
    }
    public Map<String,Object> event(long patient, long goal, long sequence) {
        List<Map<String,Object>> rows=CommonCareGoalRepository.query(jdbc,"SELECT OCTET_LENGTH(event_json) AS event_bytes,"
                +"CASE WHEN OCTET_LENGTH(event_json)<=61440 THEN event_json ELSE NULL END AS event_json "
                +"FROM common_care_goal_event WHERE goal_id=? AND sequence=? AND patient_id=?",
                (r,n)->CommonCareGoalRepository.readEvent(r),id(goal),id(sequence),id(patient));
        if (rows.isEmpty()) throw unavailable();
        Map<String,Object> result=rows.get(0);
        if (!Objects.equals(result.get("patientId"),patient)||!Objects.equals(result.get("goalId"),goal)||!Objects.equals(result.get("sequence"),sequence)) throw integrity();
        return result;
    }
    /** Names only narrow already patient-indexed relationship sources; never an all-user search. */
    public Set<Long> candidateIds(long patient, long owner, String search) {
        id(patient); id(owner);
        String name=search==null?null:text(search,50);
        Set<Long> ids=new TreeSet<>();
        String nameClause=name==null?"":" AND LOCATE(?,u.real_name)>0";
        List<Object> ownerArgs=new ArrayList<>(); ownerArgs.add(owner); if(name!=null)ownerArgs.add(name);
        ids.addAll(CommonCareGoalRepository.query(jdbc,"SELECT u.id FROM sys_user u WHERE u.id=?"+nameClause+" LIMIT 1",(r,n)->id(r.getLong(1)),ownerArgs.toArray()));
        String[][] sources={{"care_access_grant","grantee_user_id",""},{"doctor_patient_assignment","doctor_user_id"," AND x.status='ACTIVE'"},{"care_member","user_id",""}};
        for (String[] source:sources) {
            List<Object> args=new ArrayList<>();args.add(patient);if(name!=null)args.add(name);
            // Each table has an existing patient-leading relationship index. DISTINCT
            // applies before LIMIT, so multiple grant-role rows never hide another ID.
            List<Long> found=CommonCareGoalRepository.query(jdbc,"SELECT DISTINCT x."+source[1]+" FROM "+source[0]
                    +" x JOIN sys_user u ON u.id=x."+source[1]+" WHERE x.patient_id=?"+source[2]+nameClause+" ORDER BY x."+source[1]+" LIMIT 41",
                    (r,n)->id(r.getLong(1)),args.toArray());
            if(found.size()>MAX_PAGE) throw candidatesOverflow();
            ids.addAll(found); if(ids.size()>MAX_PAGE) throw candidatesOverflow();
        }
        return Collections.unmodifiableSet(ids);
    }
    public Map<Long,String> names(Collection<Long> subjects) {
        if(subjects==null||subjects.size()>81)throw invalid();
        SortedSet<Long> ids=new TreeSet<>();for(Long subject:subjects)ids.add(id(subject));
        Map<Long,String> result=new LinkedHashMap<>();if(ids.isEmpty())return result;
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        CommonCareGoalRepository.query(jdbc,"SELECT id,LEFT(real_name,50) AS display_name FROM sys_user WHERE id IN ("+placeholders+") ORDER BY id LIMIT 82",
                (r,n)->{String name=r.getString("display_name");
                    if(name!=null){requireBytes(name,200);if(name.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)))name=null;}
                    result.put(id(r.getLong("id")),name);return 0;},ids.toArray());
        return result;
    }
    static void page(int limit) { if(limit<1||limit>MAX_PAGE)throw invalid(); }
    static WorkflowException unavailable() { return new WorkflowException(404,"COMMON_GOAL_UNAVAILABLE","This common care goal or history entry is not available."); }
    private static WorkflowException candidatesOverflow() {
        return new WorkflowException(409,"COMMON_GOAL_CANDIDATES_OVERFLOW","Too many related participants. Narrow the name search before choosing a responsible account.");
    }
}
