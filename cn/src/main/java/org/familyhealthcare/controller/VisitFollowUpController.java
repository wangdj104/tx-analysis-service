package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.visitfollowup.*;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;

@RestController
@VisitFollowUpExceptionAdvice.Api
@RequestMapping("/visit-followup")
public class VisitFollowUpController {
    private final AppointmentFollowUpService appointments;
    private final VisitFollowUpQueryService queries;
    private final VisitDispositionService visits;
    private final VisitDispositionQueryService visitQueries;
    private final VisitFollowUpProperties properties;
    public VisitFollowUpController(AppointmentFollowUpService appointments,VisitFollowUpQueryService queries,VisitDispositionService visits,VisitFollowUpProperties properties,VisitDispositionQueryService visitQueries){this.appointments=Objects.requireNonNull(appointments);this.queries=Objects.requireNonNull(queries);this.visits=Objects.requireNonNull(visits);this.properties=Objects.requireNonNull(properties);this.visitQueries=Objects.requireNonNull(visitQueries);}
    /** 成功和失败都不允许缓存患者来源或当前资格。 */
    @ModelAttribute public void privateResponse(javax.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");response.setHeader("X-Content-Type-Options","nosniff");}
    @PostMapping("/commands") public Result<Map<String,Object>> command(@RequestBody String rawJson,HttpServletRequest request){query(request);Command c=parseJson(rawJson,properties.getClock());long actor=actor();return Result.ok(c.getOperation().isVisitOperation()?visits.command(actor,c):appointments.command(actor,c));}
    @GetMapping("/capabilities") public Result<Map<String,Object>> capabilities(HttpServletRequest request){Map<String,String> q=query(request,"patientId");return visitResult(queries.capabilities(actor(),positive(q.get("patientId"))));}
    @GetMapping("/appointments") public Result<Map<String,Object>> appointments(HttpServletRequest request){Map<String,String> q=query(request,"patientId","cursor","limit");return visitResult(queries.appointments(actor(),positive(q.get("patientId")),q.get("cursor"),limit(q)));}
    @GetMapping("/owners") public Result<Map<String,Object>> owners(HttpServletRequest request){Map<String,String> q=query(request,"patientId","cursor","limit");return visitResult(queries.owners(actor(),positive(q.get("patientId")),q.get("cursor"),limit(q)));}
    @GetMapping("/doctors") public Result<Map<String,Object>> doctors(HttpServletRequest request){Map<String,String> q=query(request,"patientId","cursor","limit");return visitResult(queries.doctors(actor(),positive(q.get("patientId")),q.get("cursor"),limit(q)));}
    @GetMapping("/successors") public Result<Map<String,Object>> successors(HttpServletRequest request){Map<String,String> q=query(request,"patientId","sourceKind","sourceId","cursor","limit");return visitResult(queries.successors(actor(),positive(q.get("patientId")),kind(q.get("sourceKind")),positive(q.get("sourceId")),q.get("cursor"),limit(q)));}
    @GetMapping("/next-appointments") public Result<Map<String,Object>> nextAppointments(HttpServletRequest request){Map<String,String> q=query(request,"patientId");return visitResult(queries.nextAppointments(actor(),positive(q.get("patientId"))));}
    @GetMapping("/visits") public Result<Map<String,Object>> visits(HttpServletRequest request){Map<String,String> q=query(request,"patientId","cursor","limit");return visitResult(visitQueries.visits(actor(),positive(q.get("patientId")),q.get("cursor")==null?0:positive(q.get("cursor")),limit(q)));}
    @GetMapping("/visit-actions") public Result<Map<String,Object>> visitActions(HttpServletRequest request){Map<String,String> q=query(request,"patientId","cursor","limit");return visitResult(visitQueries.actionCandidates(actor(),positive(q.get("patientId")),q.get("cursor")==null?0:positive(q.get("cursor")),limit(q)));}
    @GetMapping("/sources/{kind}/{id}") public Result<Map<String,Object>> source(@PathVariable String kind,@PathVariable String id,HttpServletRequest request){Map<String,String> q=query(request,"patientId");SourceKind sourceKind=kind(kind);return sourceKind==SourceKind.VISIT_RECORD?visitResult(visitQueries.source(actor(),positive(q.get("patientId")),positive(id))):visitResult(queries.source(actor(),positive(q.get("patientId")),sourceKind,positive(id)));}
    @GetMapping("/sources/{kind}/{id}/events") public Result<Map<String,Object>> events(@PathVariable String kind,@PathVariable String id,HttpServletRequest request){Map<String,String> q=query(request,"patientId","cursor","limit");SourceKind sourceKind=kind(kind);long after=q.get("cursor")==null?0:positive(q.get("cursor"));return sourceKind==SourceKind.VISIT_RECORD?visitResult(visitQueries.events(actor(),positive(q.get("patientId")),positive(id),after,limit(q))):visitResult(queries.events(actor(),positive(q.get("patientId")),sourceKind,positive(id),after,limit(q)));}
    /** 限额计入实际本地化响应外壳，不能只计算data后再添时间戳。 */
    private static Result<Map<String,Object>> visitResult(Map<String,Object> data){Result<Map<String,Object>> result=Result.ok(data);requireBytes(org.familyhealthcare.service.workflow.WorkflowData.json(result),RESPONSE_BYTES);return result;}
    private static long actor(){Long actor=CurrentUserUtil.getCurrentUserId();if(actor==null)throw new WorkflowException(403,"ACCESS_DENIED","Current account access is required.");return id(actor);}
    private static SourceKind kind(String value){try{if(value==null)throw new IllegalArgumentException();return SourceKind.valueOf(value);}catch(IllegalArgumentException e){throw invalid("A typed appointment or visit source is required.");}}
    private static Map<String,String> query(HttpServletRequest request,String...allowed){Set<String> fields=new HashSet<>(Arrays.asList(allowed));Map<String,String> result=new LinkedHashMap<>();for(Map.Entry<String,String[]> q:request.getParameterMap().entrySet()){if(!fields.contains(q.getKey())||q.getValue().length!=1)throw invalid("Unknown or repeated query field.");result.put(q.getKey(),q.getValue()[0]);}return result;}
    private static long positive(String value){if(value==null||!value.matches("[1-9][0-9]{0,15}"))throw invalid("A safe positive integer is required.");try{return id(Long.valueOf(value));}catch(NumberFormatException e){throw invalid("A safe positive integer is required.");}}
    private static int limit(Map<String,String> query){long value=query.containsKey("limit")?positive(query.get("limit")):DEFAULT_PAGE;if(value>MAX_PAGE)throw invalid("Page size must be between 1 and 25.");return(int)value;}
}
