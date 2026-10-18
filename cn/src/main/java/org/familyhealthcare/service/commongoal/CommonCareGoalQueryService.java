package org.familyhealthcare.service.commongoal;

import org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService;
import org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService.CommonGoalPermission;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.familyhealthcare.service.commongoal.CommonCareGoalContracts.*;

/** Current READ is checked before loading and again before disclosing a bounded projection. */
@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class CommonCareGoalQueryService {
    private final JdbcTemplate jdbc;
    private final CommonCareGoalRepository repository;
    private final CommonCareGoalReadRepository reads;
    private final CommonCareGoalAuthorization authorization;
    private final FamilyAssistanceAuthorizationService family;
    private final CommonCareGoalSchema schema;
    private final CommonCareGoalProperties properties;
    public CommonCareGoalQueryService(JdbcTemplate jdbc,CommonCareGoalRepository repository,CommonCareGoalReadRepository reads,
                                      CommonCareGoalAuthorization authorization,FamilyAssistanceAuthorizationService family,
                                      CommonCareGoalSchema schema,CommonCareGoalProperties properties) {
        this.jdbc=jdbc;this.repository=repository;this.reads=reads;this.authorization=authorization;
        this.family=family;this.schema=schema;this.properties=properties;
    }
    public Map<String,Object> capabilities(long actor,long patient) {
        authorization.requireRead(actor,patient);CommonCareGoalSchema.Status status=inspect();
        boolean ready=status==CommonCareGoalSchema.Status.READY,write=authorization.canWrite(actor,patient),owner=authorization.isRecordOwner(actor,patient);
        return finish(actor,patient,WorkflowData.map("patientId",patient,"schemaStatus",status.name(),"enabled",properties.isEnabled(),
                "readable",ready,"canWrite",ready&&properties.isEnabled()&&write,"recordOwner",owner,
                "canCreate",ready&&properties.isEnabled()&&write,"canChooseNominee",ready&&properties.isEnabled()&&write&&owner));
    }
    public Map<String,Object> list(long actor,long patient,String state,String cursor,int limit) {
        begin(actor,patient);CommonCareGoalReadRepository.page(limit);
        String filter=state==null?"OPEN":state;if(!filter.equals("OPEN"))choice(filter,State.class);
        String scope="cg1:list:"+id(patient)+":"+filter;
        List<Goal> found=reads.goals(patient,filter,CommonCareGoalCursor.position(cursor,scope),limit);
        boolean more=found.size()>limit;if(more)found=new ArrayList<>(found.subList(0,limit));
        Set<Long> subjects=new TreeSet<>();for(Goal goal:found){subjects.add(goal.getCreatorId());if(goal.getContent().getNomineeId()!=null)subjects.add(goal.getContent().getNomineeId());}
        Map<Long,CommonGoalPermission> permissions=family.commonGoalPermissions(patient,subjects);Map<Long,String> names=names(permissions);
        List<Map<String,Object>> items=new ArrayList<>();
        for(Goal goal:found){Long nominee=goal.getContent().getNomineeId();boolean eligible=canWrite(permissions,nominee);
            items.add(WorkflowData.map("goalId",goal.getId(),"title",goal.getContent().getTitle(),"state",goal.getState().name(),"version",goal.getVersion(),
                    "cycle",goal.getCycle(),"reviewDate",goal.getContent().getReviewDate(),"creator",participant(goal.getCreatorId(),names),
                    "nominee",participant(nominee,names),"acceptedBy",goal.getAcceptedBy(),"responsibilityStatus",responsibility(goal,eligible),"updatedAt",goal.asMap().get("updatedAt")));
        }
        return finish(actor,patient,WorkflowData.map("items",items,"nextCursor",more?CommonCareGoalCursor.encode(scope,found.get(found.size()-1).getId()):null));
    }
    public Map<String,Object> current(long actor,long patient,long goalId) {
        begin(actor,patient);Goal goal=goal(patient,goalId);Set<Long> subjects=new TreeSet<>();subjects.add(goal.getCreatorId());
        if(goal.getContent().getNomineeId()!=null)subjects.add(goal.getContent().getNomineeId());
        Map<Long,CommonGoalPermission> permissions=family.commonGoalPermissions(patient,subjects);Map<Long,String> names=names(permissions);
        boolean eligible=canWrite(permissions,goal.getContent().getNomineeId());long owner=authorization.recordOwner(patient);
        Map<String,Object> progress=repository.latestEvent(patient,goalId,goal.getCycle(),Operation.ADD_PROGRESS),feedback=repository.latestEvent(patient,goalId,goal.getCycle(),Operation.ADD_FEEDBACK);
        Map<String,Object> closure=goal.isOpen()?null:reads.event(patient,goalId,goal.getVersion());
        return finish(actor,patient,WorkflowData.map("goal",goal.asMap(),"creator",participant(goal.getCreatorId(),names),"nominee",participant(goal.getContent().getNomineeId(),names),
                "recordOwner",actor==owner,"responsibilityStatus",responsibility(goal,eligible),"nomineeCurrentlyEligible",eligible,
                "latestProgress",entry(progress),"latestFeedback",entry(feedback),"closure",entry(closure),
                "completionReady",CommonCareGoalRules.completionReady(goal,eligible,progress,feedback),
                "allowedActions",CommonCareGoalRules.actions(actor,owner,goal,properties.isEnabled()&&authorization.canWrite(actor,patient),eligible,progress,feedback)));
    }
    public Map<String,Object> history(long actor,long patient,long goalId,String cursor,int limit) {
        begin(actor,patient);goal(patient,goalId);CommonCareGoalReadRepository.page(limit);
        String scope="cg1:history:"+id(patient)+":"+id(goalId);
        List<Map<String,Object>> found=reads.history(patient,goalId,CommonCareGoalCursor.position(cursor,scope),limit);
        boolean more=found.size()>limit;if(more)found=new ArrayList<>(found.subList(0,limit));
        Set<Long> subjects=new TreeSet<>();for(Map<String,Object> event:found)subjects.add(id(event.get("actorId")));
        Map<Long,String> names=names(family.commonGoalPermissions(patient,subjects));
        for(Map<String,Object> event:found)event.put("actorDisplayName",names.get(id(event.get("actorId"))));
        return finish(actor,patient,WorkflowData.map("items",found,"nextCursor",more?CommonCareGoalCursor.encode(scope,id(found.get(found.size()-1).get("sequence"))):null));
    }
    public Map<String,Object> historyDetail(long actor,long patient,long goalId,long sequence) {
        begin(actor,patient);goal(patient,goalId);
        return finish(actor,patient,reads.event(patient,goalId,sequence));
    }
    public Map<String,Object> receipt(long actor,long patient,String key) {
        begin(actor,patient);Map<String,Object> receipt=authorization.receipt(repository,actor,patient,commandKey(key));
        return finish(actor,patient,WorkflowData.map("status",receipt==null?"UNKNOWN":"COMMITTED","receipt",receipt,"safeToUseNewKey",false));
    }
    public Map<String,Object> candidates(long actor,long patient,String name) {
        requireOwner(actor,patient);schema.requireReadable(inspect());
        Set<Long> subjects=reads.candidateIds(patient,authorization.recordOwner(patient),name);
        Map<Long,CommonGoalPermission> permissions=family.commonGoalPermissions(patient,subjects);Set<Long> eligible=new TreeSet<>();
        for(Map.Entry<Long,CommonGoalPermission> entry:permissions.entrySet())if(entry.getValue().canWrite())eligible.add(entry.getKey());
        Map<Long,String> names=reads.names(eligible);List<Map<String,Object>> items=new ArrayList<>();
        for(Long user:eligible)items.add(participant(user,names));
        requireOwner(actor,patient);return finish(actor,patient,WorkflowData.map("items",items));
    }
    private CommonCareGoalSchema.Status inspect(){return jdbc.execute((ConnectionCallback<CommonCareGoalSchema.Status>)schema::inspectStorage);}
    private void begin(long actor,long patient){authorization.requireRead(actor,patient);schema.requireReadable(inspect());}
    private void requireOwner(long actor,long patient){authorization.requireWrite(actor,patient);if(!authorization.isRecordOwner(actor,patient))throw new WorkflowException(403,"COMMON_GOAL_OWNER_REQUIRED","Only the current record owner can choose from related participants.");}
    private Goal goal(long patient,long goalId){Goal result=repository.find(patient,goalId,false);if(result==null)throw CommonCareGoalReadRepository.unavailable();return result;}
    private Map<String,Object> finish(long actor,long patient,Map<String,Object> result){
        // Includes JSON escaping overhead. The largest list is 40 bounded summaries;
        // only an explicit detail request can load one complete 60KiB event.
        requireBytes(WorkflowData.json(result),256*1024);authorization.requireRead(actor,patient);return result;
    }
    private Map<Long,String> names(Map<Long,CommonGoalPermission> permissions){Set<Long> visible=new TreeSet<>();for(Map.Entry<Long,CommonGoalPermission> e:permissions.entrySet())if(e.getValue().canRead())visible.add(e.getKey());return reads.names(visible);}
    private static boolean canWrite(Map<Long,CommonGoalPermission> permissions,Long user){return user!=null&&permissions.containsKey(user)&&permissions.get(user).canWrite();}
    private static Map<String,Object> participant(Long user,Map<Long,String> names){return user==null?null:WorkflowData.map("userId",user,"displayName",names.get(user));}
    private static String responsibility(Goal goal,boolean eligible){if(!goal.isOpen())return "HISTORIC";if(goal.getContent().getNomineeId()==null)return "UNASSIGNED";if(!eligible)return "UNRESOLVED";return goal.getAcceptedBy()==null?"AWAITING_ACCEPTANCE":"ACCEPTED";}
    private static Map<String,Object> entry(Map<String,Object> event){return event==null?null:WorkflowData.map("sequence",event.get("sequence"),"cycle",event.get("cycle"),"operation",event.get("operation"),"actorId",event.get("actorId"),"recordedAt",event.get("recordedAt"),"reason",event.get("reason"),"note",event.get("note"),"reportedDate",event.get("reportedDate"),"feedbackSource",event.get("feedbackSource"),"result",event.get("result"));}
}
