package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.alertworkflow.*;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static org.familyhealthcare.service.alertworkflow.AlertWorkflowContracts.*;

/** Narrow command API. Request roles, actor IDs and arbitrary status changes are never accepted. */
@RestController
@AlertWorkflowExceptionAdvice.Api
@RequestMapping("/alert-workflows")
public class AlertWorkflowController {
    private static final Map<String,String> ACTIONS;
    static {Map<String,String> actions=new LinkedHashMap<>();for(String op:AlertWorkflowService.OPERATIONS)actions.put(op.toLowerCase(Locale.ROOT).replace('_','-'),op);ACTIONS=Collections.unmodifiableMap(actions);}
    private final AlertWorkflowProperties properties;private final ObjectProvider<AlertWorkflowService> service;private final ObjectProvider<AlertWorkflowQueryService> queries;private final AlertWorkflowAuthorizationService auth;
    public AlertWorkflowController(AlertWorkflowProperties properties,ObjectProvider<AlertWorkflowService> service,ObjectProvider<AlertWorkflowQueryService> queries,AlertWorkflowAuthorizationService auth){this.properties=properties;this.service=service;this.queries=queries;this.auth=auth;}
    @GetMapping("/capabilities") public Result<Map<String,Object>> capabilities(HttpServletRequest request){long actor=authenticated();Map<String,String> q=query(request,"patientId");auth.requireAccount(actor);if(!properties.isEnabled())return Result.ok(Collections.singletonMap("enabled",false));if(!q.containsKey("patientId"))return Result.ok(Collections.singletonMap("enabled",true));return Result.ok(queries.getObject().capabilities(actor,positive(q.get("patientId"))));}
    @GetMapping public Result<Map<String,Object>> list(HttpServletRequest request){long actor=actor();Map<String,String> q=query(request,"patientId","queue","cursor","limit");return Result.ok(queries.getObject().list(actor,positive(q.get("patientId")),q.getOrDefault("queue","TODAY"),q.get("cursor"),limit(q)));}
    @GetMapping("/assignees") public Result<List<Map<String,Object>>> assignees(HttpServletRequest request){long actor=actor();Map<String,String> q=query(request,"patientId","mode");return Result.ok(queries.getObject().assignees(actor,positive(q.get("patientId")),q.getOrDefault("mode","PERSONAL")));}
    @GetMapping("/{id}") public Result<Map<String,Object>> detail(@PathVariable String id,HttpServletRequest request){long actor=actor();query(request);return Result.ok(queries.getObject().detail(actor,positive(id)));}
    @GetMapping("/{id}/events") public Result<Map<String,Object>> events(@PathVariable String id,HttpServletRequest request){long actor=actor();Map<String,String> q=query(request,"cursor","limit");return Result.ok(queries.getObject().events(actor,positive(id),q.get("cursor"),limit(q)));}
    @PostMapping("/adopt") public Result<Map<String,Object>> adopt(@RequestBody Map<String,Object> requestBody,HttpServletRequest request){long actor=actor();query(request);Command command=envelope(requestBody);if(command.version!=0)throw AlertWorkflowException.invalid("Adoption expectedVersion must be zero.");Map<String,Object> body=new LinkedHashMap<>(command.body);String type=choice(body.remove("sourceType"),"sourceType",SOURCE_TYPES);long source=requireId(body.remove("sourceId"),"sourceId");String metric=choice(body.remove("metric"),"metric",METRICS);return Result.ok(service.getObject().adopt(actor,type,source,metric,body,command.key));}
    @PostMapping("/{id}/{action}") public Result<Map<String,Object>> command(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> requestBody,HttpServletRequest request){long actor=actor();query(request);String operation=ACTIONS.get(action);if(operation==null)throw AlertWorkflowException.invalid("Unsupported workflow action.");Command command=envelope(requestBody);return Result.ok(service.getObject().command(actor,positive(id),operation,command.body,command.key,command.version));}
    private long authenticated(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null||actor<=0)throw AlertWorkflowException.denied();return actor;}
    private long actor(){long actor=authenticated();properties.requireEnabled();auth.requireActiveActor(actor);return actor;}
    static Map<String,String> query(HttpServletRequest request,String...fields){Set<String> allowed=new HashSet<>(Arrays.asList(fields));Map<String,String> result=new LinkedHashMap<>();for(Map.Entry<String,String[]> entry:request.getParameterMap().entrySet()){if(!allowed.contains(entry.getKey())||entry.getValue().length!=1)throw AlertWorkflowException.invalid("Unsupported or repeated query field.");result.put(entry.getKey(),entry.getValue()[0]);}return result;}
    static long positive(String value){if(value==null||!value.matches("[1-9][0-9]*"))throw AlertWorkflowException.invalid("A positive integer ID is required.");try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw AlertWorkflowException.invalid("Integer ID is out of range.");}}
    private static int limit(Map<String,String> query){long value=query.containsKey("limit")?positive(query.get("limit")):50;if(value>100)throw AlertWorkflowException.invalid("limit must be between 1 and 100.");return(int)value;}
    @SuppressWarnings("unchecked") private static Command envelope(Map<String,Object> raw){fields(raw,new HashSet<>(Arrays.asList("commandKey","expectedVersion","body")));String key=commandKey(raw.get("commandKey"));long version=requireVersion(raw.get("expectedVersion"));if(!(raw.get("body") instanceof Map))throw AlertWorkflowException.invalid("body must be an object.");return new Command(key,version,(Map<String,Object>)raw.get("body"));}
    private static final class Command {final String key;final long version;final Map<String,Object> body;Command(String key,long version,Map<String,Object> body){this.key=key;this.version=version;this.body=body;}}
}
