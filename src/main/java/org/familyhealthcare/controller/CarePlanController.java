package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.util.*;

/** Authenticated HTTP boundary; all clinical decisions stay with the current-authority services. */
@RestController
@CarePlanExceptionAdvice.Api
@RequestMapping("/care-plans")
public class CarePlanController {
    private final CarePlanProperties properties;
    private final ObjectProvider<CarePlanQueryService> queries;
    private final ObjectProvider<CarePlanService> plans;
    private final ObjectProvider<CarePlanActionService> actions;
    private final ObjectProvider<CarePlanTimelineProjector> timeline;
    private final ObjectProvider<CarePlanNotificationWorker> notifications;
    public CarePlanController(CarePlanProperties properties,ObjectProvider<CarePlanQueryService> queries,
            ObjectProvider<CarePlanService> plans,ObjectProvider<CarePlanActionService> actions,
            ObjectProvider<CarePlanTimelineProjector> timeline,ObjectProvider<CarePlanNotificationWorker> notifications) {
        this.properties=properties;this.queries=queries;this.plans=plans;this.actions=actions;this.timeline=timeline;this.notifications=notifications;
    }
    @GetMapping("/capabilities") public Result<Map<String,Object>> capabilities(HttpServletRequest request) {
        authenticated();query(request);return Result.ok(Collections.singletonMap("enabled",properties.isEnabled()));
    }
    @GetMapping public Result<Map<String,Object>> list(HttpServletRequest request) {
        long actor=actor();Map<String,String> q=query(request,"patientId","queue","cursor","limit","dueBefore");
        Long patient=q.containsKey("patientId")?positive(q.get("patientId")):null;
        String queue=q.getOrDefault("queue","TODAY");
        return Result.ok(queries.getObject().list(actor,patient,queue,q.get("cursor"),pageLimit(q),q.get("dueBefore")));
    }
    @GetMapping("/assignees") public Result<List<Map<String,Object>>> assignees(HttpServletRequest request) {
        long actor=actor();Map<String,String> q=query(request,"patientId");return Result.ok(queries.getObject().assignees(actor,positive(q.get("patientId"))));
    }
    @GetMapping("/{id}") public Result<Map<String,Object>> detail(@PathVariable String id,HttpServletRequest request) {
        long actor=actor();query(request);return Result.ok(queries.getObject().detail(actor,positive(id)));
    }
    @GetMapping("/{id}/revisions") public Result<Map<String,Object>> revisions(@PathVariable String id,HttpServletRequest request) {
        long actor=actor();Map<String,String> q=query(request,"cursor","limit");return Result.ok(queries.getObject().listRevisions(actor,positive(id),q.get("cursor"),pageLimit(q)));
    }
    @GetMapping("/{id}/revisions/{revisionId}") public Result<Map<String,Object>> revision(@PathVariable String id,@PathVariable String revisionId,HttpServletRequest request) {
        long actor=actor();query(request);return Result.ok(queries.getObject().revision(actor,positive(id),positive(revisionId)));
    }
    @GetMapping("/{id}/events") public Result<Map<String,Object>> events(@PathVariable String id,HttpServletRequest request) {
        long actor=actor();Map<String,String> q=query(request,"cursor","limit");return Result.ok(timeline.getObject().events(actor,positive(id),q.get("cursor"),pageLimit(q)));
    }
    @PostMapping public Result<Map<String,Object>> create(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body,"patientId","title","instructions","planType","actions","legacySourceId");return Result.ok(plans.getObject().createDraft(actor,c.body,c.key));
    }
    @PostMapping("/{id}/revisions/{revisionId}/save") public Result<Map<String,Object>> save(@PathVariable String id,@PathVariable String revisionId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body,"patientId","title","instructions","planType","actions","legacySourceId");return Result.ok(plans.getObject().saveDraft(actor,positive(id),positive(revisionId),c.body,c.key,c.version));
    }
    @PostMapping("/{id}/revisions") public Result<Map<String,Object>> revise(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body);return Result.ok(plans.getObject().createRevision(actor,positive(id),c.key,c.version));
    }
    @PostMapping("/{id}/revisions/{revisionId}/publish") public Result<Map<String,Object>> publish(@PathVariable String id,@PathVariable String revisionId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body,"currentRevisionId","supersededActionDigest");return Result.ok(plans.getObject().publish(actor,positive(id),positive(revisionId),c.body,c.key,c.version));
    }
    @PostMapping("/{id}/cancel") public Result<Map<String,Object>> cancel(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body,"reason");Object reason=c.body.get("reason");if(reason!=null&&!(reason instanceof String))throw invalid();return Result.ok(plans.getObject().transitionPlan(actor,positive(id),"CANCEL",(String)reason,c.key,c.version));
    }
    @PostMapping("/{id}/close") public Result<Map<String,Object>> close(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body);return Result.ok(plans.getObject().transitionPlan(actor,positive(id),"CLOSE",null,c.key,c.version));
    }
    @PostMapping("/actions/{id}/receipts") public Result<Map<String,Object>> receipt(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body,"note","occurredAt","entryMode","evidence");return Result.ok(actions.getObject().submit(actor,positive(id),c.body,c.key,c.version));
    }
    @PostMapping("/actions/{id}/help") public Result<Map<String,Object>> help(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body,"note");return Result.ok(actions.getObject().help(actor,positive(id),c.body,c.key,c.version));
    }
    @PostMapping("/actions/{id}/follow-ups") public Result<Map<String,Object>> followUp(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body,"kind","note");return Result.ok(actions.getObject().followUp(actor,positive(id),c.body,c.key,c.version));
    }
    @PostMapping("/actions/{id}/reviews") public Result<Map<String,Object>> review(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);Command c=command(body,"decision","note");return Result.ok(actions.getObject().review(actor,positive(id),c.body,c.key,c.version));
    }
    @GetMapping("/notifications") public Result<Map<String,Object>> notifications(HttpServletRequest request) {
        long actor=actor();Map<String,String> q=query(request,"cursor","limit");return Result.ok(notifications.getObject().listDeliveryStatus(actor,q.get("cursor"),pageLimit(q)));
    }
    @PostMapping("/notifications/{id}/retry") public Result<Map<String,Object>> retry(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long actor=actor();query(request);if(body.size()!=1||!(body.get("duplicateRiskAcknowledged") instanceof Boolean))throw invalid();return Result.ok(notifications.getObject().retry(actor,positive(id),(Boolean)body.get("duplicateRiskAcknowledged")));
    }
    private long actor(){long actor=authenticated();properties.requireEnabled();return actor;}
    private long authenticated(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null)throw CarePlanException.denied();return actor;}
    static Map<String,String> query(HttpServletRequest request,String...fields){
        Set<String> allowed=new HashSet<>(Arrays.asList(fields));Map<String,String> result=new LinkedHashMap<>();
        for(Map.Entry<String,String[]> e:request.getParameterMap().entrySet()){
            if(!allowed.contains(e.getKey())||e.getValue().length!=1)throw invalid();result.put(e.getKey(),e.getValue()[0]);
        }return result;
    }
    static long positive(String value){
        if(value==null||!value.matches("[1-9][0-9]*"))throw invalid();try{return Long.parseLong(value);}catch(NumberFormatException ex){throw invalid();}
    }
    private static int pageLimit(Map<String,String> q){long value=q.containsKey("limit")?positive(q.get("limit")):50;if(value>100)throw invalid();return(int)value;}
    private static Command command(Map<String,Object> body,String...fields){
        Set<String> allowed=new HashSet<>(Arrays.asList(fields));allowed.add("commandKey");allowed.add("expectedVersion");
        if(body==null||!allowed.containsAll(body.keySet())||!(body.get("commandKey") instanceof String))throw invalid();
        String key=CarePlanCommandStore.validateKey((String)body.get("commandKey"));Object value=body.get("expectedVersion");long version;
        try {if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)version=((Number)value).longValue();else if(value instanceof BigInteger)version=((BigInteger)value).longValueExact();else throw invalid();}
        catch(ArithmeticException ex){throw invalid();}if(version<0)throw invalid();
        Map<String,Object> clean=new LinkedHashMap<>(body);clean.remove("commandKey");clean.remove("expectedVersion");return new Command(clean,key,version);
    }
    private static CarePlanException invalid(){return CarePlanException.invalid("Invalid care-plan request fields or identifiers.");}
    private static final class Command {final Map<String,Object>body;final String key;final long version;Command(Map<String,Object>b,String k,long v){body=b;key=k;version=v;}}
}
