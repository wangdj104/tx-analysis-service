package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpRepository.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;

/** 预约专用固定读取组：元数据一次、身份两次、权限前后各一次；不预读就诊或用药正文。 */
@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class VisitFollowUpQueryService {
    private final VisitFollowUpRepository repo;
    private final VisitFollowUpAuthorizationService auth;
    private final VisitFollowUpProperties properties;
    private final VisitFollowUpSourceBridge bridge;
    public VisitFollowUpQueryService(VisitFollowUpRepository repo,VisitFollowUpAuthorizationService auth,VisitFollowUpProperties properties,VisitFollowUpSourceBridge bridge) {
        this.repo=Objects.requireNonNull(repo);this.auth=Objects.requireNonNull(auth);this.properties=Objects.requireNonNull(properties);this.bridge=Objects.requireNonNull(bridge);
    }
    public Map<String,Object> capabilities(long actor,long patient) {
        VisitFollowUpSchema.Availability availability=bridge.availability();boolean installed=availability.isReadAvailable();
        if(!installed)return map("legacyVisitsAllowed",!properties.isEnabled(),"enabled",false,"schemaStatus","ABSENT","reminderAvailable",false,"appointments",false,"visits",false,"carePlan",false,"allowedCommands",Collections.emptyList());
        VisitFollowUpAuthorizationService.Snapshot before=auth.snapshot(patient,Collections.singleton(actor)),after=auth.snapshot(patient,Collections.singleton(actor));
        if(!before.signature().equals(after.signature()))throw changed();
        List<String> commands=new ArrayList<>();if(availability.isWriteAvailable(properties.isEnabled())){if(after.canAppointmentWrite(actor))commands.add("TRACK_APPOINTMENT");if(after.canClinical(actor))commands.add("CREATE_VISIT");}
        return map("legacyVisitsAllowed",!properties.isEnabled(),"enabled",properties.isEnabled(),"schemaStatus",availability.name(),"reminderAvailable",availability.isWriteAvailable(properties.isEnabled()),"appointments",after.canAppointmentRead(actor),"visits",after.canVisitRead(actor),"carePlan",after.canPlanRead(actor),"allowedCommands",commands);
    }
    public Map<String,Object> appointments(long actor,long patient,String cursor,int limit) {
        pageSize(limit);VisitFollowUpSchema.Availability availability=requireStorage();int kind=1;long after=0;
        if(cursor!=null&&!cursor.isEmpty()){String[] p=cursor.split(":",-1);if(p.length!=2||!p[0].matches("[12]")||!p[1].matches("[1-9][0-9]{0,15}"))throw invalid("Invalid appointment cursor.");kind=Integer.parseInt(p[0]);try{after=id(Long.valueOf(p[1]));}catch(NumberFormatException invalid){throw invalid("Invalid appointment cursor.");}}
        List<Map<String,Object>> discovered=discover(patient,null,kind,after,limit+1);boolean more=discovered.size()>limit;
        List<Map<String,Object>> page=new ArrayList<>(discovered.subList(0,Math.min(limit,discovered.size())));
        Read before=read(actor,patient,page);if(!before.authority.canAppointmentRead(actor))throw VisitFollowUpAuthorizationService.denied();
        Read current=finish(actor,patient,page,before,null,kind,after,limit+1,discovered);
        // Use only the already-verified source set; authority expiry is evaluated again at the end.
        if(!current.authority.canAppointmentRead(actor))throw VisitFollowUpAuthorizationService.denied();
        // 页内任何来源不可披露则整页不可用，不能把被过滤的ID泄露到下一页游标。
        List<Map<String,Object>> items=new ArrayList<>();for(Map<String,Object> row:page){SourceRef ref=sourceRef(row);NativeSource nativeSource=current.sources.get(ref);if(nativeSource==null||!nativeSource.isOrdinary())throw changed();items.add(project(actor,ref,current,availability));}
        String next=more&&!page.isEmpty()?page.get(page.size()-1).get("kind_code")+":"+page.get(page.size()-1).get("source_id"):null;
        return bounded(map("items",items,"hasMore",more,"nextCursor",next,"appointmentSystemTimeZone",null,"schemaStatus",availability.name()));
    }
    public Map<String,Object> source(long actor,long patient,SourceKind kind,long source) {
        if(!kind.isAppointment())throw invalid("This endpoint only reads appointments.");VisitFollowUpSchema.Availability availability=requireStorage();SourceRef ref=new SourceRef(kind,source);
        List<Map<String,Object>> discovered=discover(patient,ref,1,0,1);if(discovered.size()!=1)throw VisitFollowUpAuthorizationService.denied();
        Read before=read(actor,patient,discovered);NativeSource nativeSource=before.sources.get(ref);
        if(nativeSource==null||!nativeSource.isOrdinary()||!canRead(actor,nativeSource,before.authority))throw VisitFollowUpAuthorizationService.denied();
        boolean reminderVerified=verifyReminderReference(ref,before);
        Read current=finish(actor,patient,discovered,before,ref,1,0,1,discovered);
        if(!canRead(actor,current.sources.get(ref),current.authority))throw VisitFollowUpAuthorizationService.denied();
        Map<String,Object> result=project(actor,ref,current,availability);result.put("reminder",reminderProjection(ref,current,reminderVerified));return bounded(result);
    }
    public Map<String,Object> events(long actor,long patient,SourceKind kind,long source,long after,int limit) {
        pageSize(limit);if(after<0||after>MAX_ID||!kind.isAppointment())throw invalid("Invalid event cursor.");requireStorage();SourceRef ref=new SourceRef(kind,source);
        List<Map<String,Object>> discovered=discover(patient,ref,1,0,1);if(discovered.size()!=1)throw VisitFollowUpAuthorizationService.denied();Read before=read(actor,patient,discovered);
        if(!canRead(actor,before.sources.get(ref),before.authority)||!before.sources.get(ref).isOrdinary())throw VisitFollowUpAuthorizationService.denied();
        Map<String,Object> state=before.states.get(ref);List<Map<String,Object>> rows=state==null?Collections.emptyList():repo.rows("SELECT id,event_type,actor_id,CASE WHEN OCTET_LENGTH(COALESCE(actor_name,''))+OCTET_LENGTH(COALESCE(actor_relation,''))+OCTET_LENGTH(COALESCE(reason,''))<=65536 THEN actor_name ELSE NULL END AS actor_name,actor_relation,occurred_at_utc,recorded_at,CASE WHEN OCTET_LENGTH(COALESCE(actor_name,''))+OCTET_LENGTH(COALESCE(actor_relation,''))+OCTET_LENGTH(COALESCE(reason,''))<=65536 THEN reason ELSE NULL END AS reason,OCTET_LENGTH(COALESCE(actor_name,''))+OCTET_LENGTH(COALESCE(actor_relation,''))+OCTET_LENGTH(COALESCE(reason,'')) AS text_bytes FROM visit_followup_event WHERE state_id=? AND patient_id=? AND id>? ORDER BY id LIMIT ?",limit+1,state.get("id"),patient,after,limit+1);
        for(Map<String,Object> event:rows)if(number(event.remove("text_bytes"))>NATIVE_TEXT_BYTES)throw VisitFollowUpContracts.limit();
        Read current=finish(actor,patient,discovered,before,ref,1,0,1,discovered);if(!canRead(actor,current.sources.get(ref),current.authority))throw VisitFollowUpAuthorizationService.denied();boolean more=rows.size()>limit;List<Map<String,Object>> page=new ArrayList<>(rows.subList(0,Math.min(limit,rows.size())));
        return bounded(map("items",normalize(page),"hasMore",more,"nextCursor",more?page.get(page.size()-1).get("id"):null));
    }
    /** 责任人只分页发现本患者当前合格账号；游标只取已返回ID，绝不全表补扫。 */
    public Map<String,Object> owners(long actor,long patient,String cursor,int limit) {return people(actor,patient,cursor,limit,false);}
    /** 预约医生沿原命令的当前医生账号门，不擅自要求既有患者分配。 */
    public Map<String,Object> doctors(long actor,long patient,String cursor,int limit) {return people(actor,patient,cursor,limit,true);}
    private Map<String,Object> people(long actor,long patient,String cursor,int limit,boolean doctors) {
        pageSize(limit);requireStorage();String lane=doctors?"DOCTORS":"OWNERS";long after=pickerCursor(cursor,actor,patient,lane);
        List<Map<String,Object>> discovered;
        if(doctors)discovered=repo.rows("SELECT u.id FROM sys_user u WHERE u.id>? AND u.status=1 AND COALESCE(u.deleted,0)=0 AND EXISTS(SELECT 1 FROM sys_user_role m JOIN sys_role r ON r.id=m.role_id WHERE m.user_id=u.id AND LOWER(RTRIM(r.role_code))='doctor' AND r.status=1 AND COALESCE(r.deleted,0)=0) ORDER BY u.id LIMIT ?",limit+1,after,limit+1);
        else {
            // SQL先筛当前资格再取页；A仍是最终授权，游标只使用实际返回的合格ID。
            String family=roleSql("u.id","'family'"),eligible=roleSql("u.id","'family','doctor','nurse'"),doctor=roleSql("u.id","'doctor'");
            String qualified="u.status=1 AND COALESCE(u.deleted,0)=0 AND "+roleSql("u.id",null)+" AND (u.id=p.user_id OR ("+doctor+" AND EXISTS(SELECT 1 FROM doctor_patient_assignment da WHERE da.patient_id=p.id AND da.doctor_user_id=u.id AND da.status='ACTIVE')) OR ("+eligible+" AND EXISTS(SELECT 1 FROM care_access_grant g WHERE g.patient_id=p.id AND g.grantee_user_id=u.id AND g.status='ACTIVE' AND (g.expires_at IS NULL OR g.expires_at>?) AND "+appointmentWriteSql("g")+")) OR (NOT EXISTS(SELECT 1 FROM care_access_grant ga WHERE ga.patient_id=p.id AND ga.grantee_user_id=u.id) AND "+family+" AND EXISTS(SELECT 1 FROM care_member m WHERE m.patient_id=p.id AND m.user_id=u.id AND "+appointmentWriteSql("m")+")))";
            discovered=repo.rows("SELECT x.id FROM (SELECT user_id AS id FROM patient WHERE id=? UNION SELECT doctor_user_id AS id FROM doctor_patient_assignment WHERE patient_id=? UNION SELECT grantee_user_id AS id FROM care_access_grant WHERE patient_id=? UNION SELECT user_id AS id FROM care_member WHERE patient_id=?) x JOIN sys_user u ON u.id=x.id JOIN patient p ON p.id=? WHERE x.id>? AND "+qualified+" ORDER BY x.id LIMIT ?",limit+1,patient,patient,patient,patient,patient,after,java.time.LocalDateTime.ofInstant(properties.now(),java.time.ZoneOffset.UTC),limit+1);
        }
        boolean more=discovered.size()>limit;List<Map<String,Object>> page=new ArrayList<>(discovered.subList(0,Math.min(limit,discovered.size())));SortedSet<Long> subjects=new TreeSet<>();subjects.add(actor);for(Map<String,Object> row:discovered)subjects.add(id(row.get("id")));
        VisitFollowUpAuthorizationService.Snapshot before=auth.snapshot(patient,subjects);if(!before.canAppointmentWrite(actor))throw VisitFollowUpAuthorizationService.denied();
        VisitFollowUpAuthorizationService.Snapshot end=auth.snapshot(patient,subjects);if(!before.signature().equals(end.signature()))throw changed();if(!end.canAppointmentWrite(actor))throw VisitFollowUpAuthorizationService.denied();
        // SQL是资格上界；连第26个哨兵也重核，差异时不返回任何名单或游标。
        for(Map<String,Object> row:discovered){long user=id(row.get("id"));boolean first=doctors?before.isActiveDoctor(user):before.canAppointmentWrite(user),current=doctors?end.isActiveDoctor(user):end.canAppointmentWrite(user);if(!first||!current)throw changed();}
        List<Map<String,Object>> items=new ArrayList<>();for(Map<String,Object> row:page){long user=id(row.get("id"));items.add(map("userId",user,"displayName",end.displayName(user)));}
        return bounded(map("patientId",patient,"items",items,"hasMore",more,"nextCursor",more?pickerToken(actor,patient,lane,id(page.get(page.size()-1).get("id"))):null,"pageScope",doctors?"CURRENT_DOCTORS":"CURRENT_APPOINTMENT_WRITERS","eligibilityRecheckedOnCommand",true));
    }
    private static String roleSql(String user,String roles){return "EXISTS(SELECT 1 FROM sys_user_role rm JOIN sys_role rr ON rr.id=rm.role_id WHERE rm.user_id="+user+" AND rr.status=1 AND COALESCE(rr.deleted,0)=0"+(roles==null?"":" AND LOWER(RTRIM(rr.role_code)) IN ("+roles+")")+")";}
    private static String appointmentWriteSql(String alias){return alias+".access_level IN ('WRITE','PROXY') AND ("+alias+".visible_modules IS NULL OR "+alias+".visible_modules REGEXP '^[[:space:][:cntrl:]]*$' OR UPPER("+alias+".visible_modules) REGEXP '(^|,)[[:space:][:cntrl:]]*APPOINTMENTS[[:space:][:cntrl:]]*(,|$)')";}
    /** 候选不占位；同来源原后继可明确重确认，其他前驱占用的目标不列入。 */
    public Map<String,Object> successors(long actor,long patient,SourceKind kind,long source,String cursor,int limit) {
        pageSize(limit);if(!kind.isAppointment())throw invalid("A typed appointment is required.");requireStorage();String lane=kind.name()+"-"+source;long after=pickerCursor(cursor,actor,patient,lane);boolean legacy=kind==SourceKind.CARE_ITEM_APPOINTMENT;
        String table=kind.tableName(),column=legacy?"care_item_id":"care_appointment_id",wall=legacy?"event_at":"start_at",doctor=legacy?"NULL":"n.doctor_user_id",ordinary=legacy?" AND n.kind='APPOINTMENT'":"",status=legacy?"OPEN":"BOOKED";
        String own="SELECT 1 AS is_origin,n.id AS source_id,"+doctor+" AS doctor_user_id,s.responsible_user_id FROM "+table+" n JOIN visit_followup_state s ON s."+column+"=n.id AND s.patient_id=n.patient_id AND s.source_kind=? WHERE n.patient_id=? AND n.id=?"+ordinary;
        String candidates="SELECT 0 AS is_origin,n.id AS source_id,"+doctor+" AS doctor_user_id,s.responsible_user_id FROM "+table+" n JOIN "+table+" origin ON origin.id=? AND origin.patient_id=n.patient_id LEFT JOIN visit_followup_state s ON s."+column+"=n.id AND s.patient_id=n.patient_id AND s.source_kind=? WHERE n.patient_id=? AND n.id>? AND n.id<>? AND n.status=?"+ordinary+" AND n."+wall+">origin."+wall+" AND (s.id IS NULL OR s.attendance='UNVERIFIED') AND NOT EXISTS(SELECT 1 FROM visit_followup_state p WHERE p.patient_id=n.patient_id AND p.successor_kind=? AND p.successor_"+column+"=n.id AND p."+column+"<>?) ORDER BY n.id LIMIT "+(limit+1);
        List<Map<String,Object>> discovery=repo.rows("("+own+") UNION ALL ("+candidates+") ORDER BY is_origin DESC,source_id",limit+2,kind.name(),patient,source,source,kind.name(),patient,after,source,status,kind.name(),source);
        boolean hasOrigin=!discovery.isEmpty()&&number(discovery.get(0).get("is_origin"))==1;List<Map<String,Object>> candidatesPage=hasOrigin?new ArrayList<>(discovery.subList(1,discovery.size())):new ArrayList<>();boolean more=candidatesPage.size()>limit;if(more)candidatesPage.remove(candidatesPage.size()-1);
        SortedSet<Long> subjects=new TreeSet<>();subjects.add(actor);Set<SourceRef> refs=new LinkedHashSet<>();for(Map<String,Object> row:discovery){refs.add(new SourceRef(kind,id(row.get("source_id"))));for(String key:Arrays.asList("doctor_user_id","responsible_user_id"))if(row.get(key)!=null)subjects.add(id(row.get(key)));}
        VisitFollowUpAuthorizationService.Snapshot first=auth.snapshot(patient,subjects);if(!first.canAppointmentWrite(actor))throw VisitFollowUpAuthorizationService.denied();if(!hasOrigin)throw changed();Read before=pickerSources(first,patient,refs);
        // 末次只核已发现ID及其当前前驱，不补扫下一页或把占用变化留到展示之后。
        List<Object> finalArgs=new ArrayList<>(Arrays.asList(kind.name(),patient,source,source,kind.name(),patient,after,source,status,kind.name(),source));List<Long> candidateIds=new ArrayList<>();for(int i=1;i<discovery.size();i++)candidateIds.add(id(discovery.get(i).get("source_id")));
        String exact=candidates.replace(" ORDER BY n.id LIMIT "," AND n.id IN ("+(candidateIds.isEmpty()?"NULL":String.join(",",Collections.nCopies(candidateIds.size(),"?")))+") ORDER BY n.id LIMIT ");finalArgs.addAll(candidateIds);
        List<Map<String,Object>> endDiscovery=repo.rows("("+own+") UNION ALL ("+exact+") ORDER BY is_origin DESC,source_id",limit+2,finalArgs.toArray());if(!canonical(discovery).equals(canonical(endDiscovery)))throw changed();Read last=pickerSources(first,patient,refs);
        VisitFollowUpAuthorizationService.Snapshot end=auth.snapshot(patient,subjects);if(!first.signature().equals(end.signature())||!before.signature().equals(last.signature()))throw changed();if(!end.canAppointmentWrite(actor))throw VisitFollowUpAuthorizationService.denied();
        SourceRef origin=new SourceRef(kind,source);NativeSource original=last.sources.get(origin);if(original==null||!original.isOrdinary()||last.states.get(origin)==null||!original.getFingerprint().equals(last.states.get(origin).get("source_fingerprint")))throw changed();
        // 全部已读候选（含哨兵）须可核实，避免部分名单及游标暴露被过滤的受限来源。
        for(Long candidateId:candidateIds){SourceRef ref=new SourceRef(kind,candidateId);NativeSource nativeSource=last.sources.get(ref);Map<String,Object> state=last.states.get(ref);if(nativeSource==null||!nativeSource.isOrdinary()||!status.equals(nativeSource.getValues().get("status"))||state!=null&&(!"UNVERIFIED".equals(state.get("attendance"))||!nativeSource.getFingerprint().equals(state.get("source_fingerprint"))))throw changed();}
        List<Map<String,Object>> items=new ArrayList<>();for(Map<String,Object> row:candidatesPage){SourceRef ref=new SourceRef(kind,id(row.get("source_id")));NativeSource nativeSource=last.sources.get(ref);items.add(map("patientId",patient,"sourceKind",kind.name(),"sourceId",ref.getId(),"startWall",normalize(nativeSource.nativeTarget().get("native_start_wall")),"nativeStatus",status,"attendance","UNVERIFIED"));}
        return bounded(map("patientId",patient,"sourceKind",kind.name(),"sourceId",source,"items",items,"hasMore",more,"nextCursor",more?pickerToken(actor,patient,lane,id(candidatesPage.get(candidatesPage.size()-1).get("source_id"))):null,"eligibilityRecheckedOnCommand",true));
    }
    /** 首页只查当前患者的两条原生最早未结束排程；不把UTC现在当成未声明时区的墙钟。 */
    public Map<String,Object> nextAppointments(long actor,long patient) {
        requireStorage();List<Map<String,Object>> selected=repo.rows("(SELECT 1 AS kind_code,id AS source_id,NULL AS doctor_user_id,CAST(event_at AS CHAR) AS start_wall,NULL AS end_wall,status AS native_status FROM care_item WHERE patient_id=? AND kind='APPOINTMENT' AND status='OPEN' ORDER BY event_at,id LIMIT 1) UNION ALL (SELECT 2 AS kind_code,id AS source_id,doctor_user_id,CAST(start_at AS CHAR) AS start_wall,CAST(end_at AS CHAR) AS end_wall,status AS native_status FROM care_appointment WHERE patient_id=? AND status='BOOKED' ORDER BY start_at,id LIMIT 1)",2,patient,patient);
        Set<SourceRef> refs=new LinkedHashSet<>();SortedSet<Long> subjects=new TreeSet<>();subjects.add(actor);for(Map<String,Object> row:selected){refs.add(sourceRef(row));if(row.get("doctor_user_id")!=null)subjects.add(id(row.get("doctor_user_id")));}
        VisitFollowUpAuthorizationService.Snapshot first=auth.snapshot(patient,subjects);if(!first.canAppointmentRead(actor))throw VisitFollowUpAuthorizationService.denied();Read before=pickerSources(first,patient,refs),last=pickerSources(first,patient,refs);VisitFollowUpAuthorizationService.Snapshot end=auth.snapshot(patient,subjects);
        if(!first.signature().equals(end.signature())||!before.signature().equals(last.signature()))throw changed();if(!end.canAppointmentRead(actor))throw VisitFollowUpAuthorizationService.denied();
        List<Map<String,Object>> lanes=new ArrayList<>();
        for(SourceKind kind:Arrays.asList(SourceKind.CARE_ITEM_APPOINTMENT,SourceKind.CARE_APPOINTMENT)) {
            SourceRef ref=null;for(SourceRef item:refs)if(item.getKind()==kind)ref=item;
            Map<String,Object> lane=map("sourceKind",kind.name(),"status","EMPTY","item",null);
            if(ref!=null) {
                NativeSource source=last.sources.get(ref);Map<String,Object> state=last.states.get(ref);
                java.time.LocalDateTime start=source==null?null:(java.time.LocalDateTime)source.nativeTarget().get("native_start_wall"),endWall=source==null?null:(java.time.LocalDateTime)source.nativeTarget().get("native_end_wall");
                Map<String,Object> discovered=null;for(Map<String,Object> row:selected)if(sourceRef(row).equals(ref))discovered=row;
                boolean valid=source!=null&&source.isOrdinary()&&previewSame(discovered,source)&&start!=null&&(kind==SourceKind.CARE_ITEM_APPOINTMENT||endWall!=null&&endWall.isAfter(start));
                valid=valid&&(state==null||"UNVERIFIED".equals(state.get("attendance"))&&source.getFingerprint().equals(state.get("source_fingerprint")));
                if(!valid)lane.put("status","UNAVAILABLE");
                else {lane.put("status","READY");lane.put("item",map("patientId",patient,"sourceKind",kind.name(),"sourceId",ref.getId(),"startWall",normalize(start),"endWall",normalize(endWall),"nativeStatus",source.getValues().get("status"),"managed",state!=null));}
            }
            lanes.add(lane);
        }
        return bounded(map("patientId",patient,"scope","EARLIEST_OPEN_PER_NATIVE_KIND","lanes",lanes,"appointmentSystemTimeZone",null));
    }
    private static boolean previewSame(Map<String,Object> discovered,NativeSource source) {
        if(discovered==null||!Objects.equals(discovered.get("native_status"),source.getValues().get("status"))||!Objects.equals(discovered.get("doctor_user_id"),source.getDoctorId()))return false;
        for(String field:Arrays.asList("start","end")) {Object value=discovered.get(field+"_wall");java.time.LocalDateTime wall=value==null?null:java.time.LocalDateTime.parse(String.valueOf(value).replace(' ','T'));if(!Objects.equals(wall,source.nativeTarget().get("native_"+field+"_wall")))return false;}
        return true;
    }
    private Read pickerSources(VisitFollowUpAuthorizationService.Snapshot authority,long patient,Set<SourceRef> refs){Map<SourceRef,NativeSource> sources=repo.appointmentSources(refs);for(NativeSource source:sources.values())if(source.getPatientId()!=patient)throw changed();return new Read(authority,sources,repo.appointmentStates(patient,refs),Collections.emptyMap());}
    private static String pickerToken(long actor,long patient,String lane,long after){return actor+"."+patient+"."+lane+"."+after;}
    private static long pickerCursor(String cursor,long actor,long patient,String lane){if(cursor==null||cursor.isEmpty())return 0;String prefix=actor+"."+patient+"."+lane+".";if(!cursor.startsWith(prefix))throw invalid("The picker cursor belongs to a different context.");String value=cursor.substring(prefix.length());if(!value.matches("[1-9][0-9]{0,15}"))throw invalid("Invalid picker cursor.");try{return id(Long.valueOf(value));}catch(NumberFormatException e){throw invalid("Invalid picker cursor.");}}
    private Read read(long actor,long patient,List<Map<String,Object>> page) {return read(actor,patient,page,false);}
    private Read read(long actor,long patient,List<Map<String,Object>> page,boolean finalPass) {
        SortedSet<Long> subjects=new TreeSet<>();subjects.add(actor);Set<SourceRef> refs=new LinkedHashSet<>();
        for(Map<String,Object> row:page) {
            refs.add(sourceRef(row));for(String field:Arrays.asList("doctor_user_id","responsible_user_id","target_doctor_id","target_responsible_user_id"))if(row.get(field)!=null)subjects.add(id(row.get(field)));
            if(row.get("successor_id")!=null) { if(!sourceRef(row).getKind().name().equals(row.get("successor_kind")))throw changed(); refs.add(new SourceRef(sourceRef(row).getKind(),id(row.get("successor_id")))); }
        }
        VisitFollowUpAuthorizationService.Snapshot authority=finalPass?null:auth.snapshot(patient,subjects);
        if(!finalPass&&!authority.canAppointmentRead(actor)&&!(page.size()==1&&Objects.equals(page.get(0).get("doctor_user_id"),actor)&&authority.isActiveDoctor(actor)))throw VisitFollowUpAuthorizationService.denied();
        Map<SourceRef,NativeSource> sources=repo.appointmentSources(refs);Map<SourceRef,Map<String,Object>> states=repo.appointmentStates(patient,refs);Set<Long> facts=new LinkedHashSet<>();
        for(NativeSource source:sources.values())if(source.getPatientId()!=patient)throw changed();
        for(Map<String,Object> state:states.values())if(state.get("current_fact_event_id")!=null)facts.add(id(state.get("current_fact_event_id")));
        Map<Long,Map<String,Object>> factRows=repo.appointmentFacts(facts);
        // 最后权限快照必须在最后native/state/fact之后，纯到期也以披露前时钟计算。
        if(finalPass)authority=auth.snapshot(patient,subjects);
        return new Read(authority,sources,states,factRows);
    }
    private Read finish(long actor,long patient,List<Map<String,Object>> page,Read before,SourceRef source,int kind,long after,int limit,List<Map<String,Object>> discovery) {
        List<Map<String,Object>> current=discoverSelected(patient,discovery);if(!canonical(discovery).equals(canonical(current)))throw changed();
        Read end=read(actor,patient,page,true);if(!before.signature().equals(end.signature()))throw changed();return end;
    }
    private List<Map<String,Object>> discover(long patient,SourceRef source,int kind,long after,int limit) {
        List<Object> args=new ArrayList<>();String natives;
        if(source==null) {
            natives="(SELECT 1 AS kind_code,id AS source_id,patient_id,NULL AS doctor_user_id FROM care_item WHERE patient_id=? AND kind='APPOINTMENT' AND ?<=1 AND id>? ORDER BY id LIMIT "+limit+") UNION ALL (SELECT 2 AS kind_code,id AS source_id,patient_id,doctor_user_id FROM care_appointment WHERE patient_id=? AND ?<=2 AND id>? ORDER BY id LIMIT "+limit+")";
            args.addAll(Arrays.asList(patient,kind,kind==1?after:0,patient,kind,kind==2?after:0));
        }else {
            boolean legacy=source.getKind()==SourceKind.CARE_ITEM_APPOINTMENT;
            natives="SELECT "+(legacy?1:2)+" AS kind_code,id AS source_id,patient_id,"+(legacy?"NULL":"doctor_user_id")+" AS doctor_user_id FROM "+source.getKind().tableName()+" WHERE patient_id=? AND id=?"+(legacy?" AND kind='APPOINTMENT'":"");args.add(patient);args.add(source.getId());
        }
        return discoveryRows(natives,args,limit);
    }
    /** 结束重核只用已发现的确切ID，不再扫描历史页，也不追加新主体。 */
    private List<Map<String,Object>> discoverSelected(long patient,List<Map<String,Object>> discovered) {
        if(discovered.isEmpty())return Collections.emptyList();List<Object> args=new ArrayList<>();List<String> parts=new ArrayList<>();
        for(int code=1;code<=2;code++){List<Long> ids=new ArrayList<>();for(Map<String,Object> row:discovered)if(number(row.get("kind_code"))==code)ids.add(id(row.get("source_id")));if(ids.isEmpty())continue;boolean legacy=code==1;parts.add("SELECT "+code+" AS kind_code,id AS source_id,patient_id,"+(legacy?"NULL":"doctor_user_id")+" AS doctor_user_id FROM "+(legacy?"care_item":"care_appointment")+" WHERE patient_id=? AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")"+(legacy?" AND kind='APPOINTMENT'":""));args.add(patient);args.addAll(ids);}
        return discoveryRows(String.join(" UNION ALL ",parts),args,discovered.size());
    }
    private List<Map<String,Object>> discoveryRows(String natives,List<Object> args,int limit) {
        String sql="SELECT x.*,s.id AS state_id,s.responsible_user_id,s.successor_kind,COALESCE(s.successor_care_item_id,s.successor_care_appointment_id) AS successor_id,t.responsible_user_id AS target_responsible_user_id,ta.doctor_user_id AS target_doctor_id FROM ("+natives+") x LEFT JOIN visit_followup_state s ON s.patient_id=x.patient_id AND ((x.kind_code=1 AND s.care_item_id=x.source_id) OR (x.kind_code=2 AND s.care_appointment_id=x.source_id)) LEFT JOIN visit_followup_state t ON t.patient_id=x.patient_id AND ((s.successor_kind='CARE_ITEM_APPOINTMENT' AND t.care_item_id=s.successor_care_item_id) OR (s.successor_kind='CARE_APPOINTMENT' AND t.care_appointment_id=s.successor_care_appointment_id)) LEFT JOIN care_appointment ta ON ta.id=s.successor_care_appointment_id AND ta.patient_id=x.patient_id ORDER BY x.kind_code,x.source_id LIMIT "+limit;
        return repo.rows(sql,limit,args.toArray());
    }
    private Map<String,Object> project(long actor,SourceRef ref,Read read,VisitFollowUpSchema.Availability availability) {
        NativeSource source=read.sources.get(ref);Map<String,Object> state=read.states.get(ref),target=source.nativeTarget();boolean managed=state!=null;
        // 指纹稳定失配同样不可拼成当前事实；只能重核，不能混用旧到诊与新排程。
        if(managed&&!source.getFingerprint().equals(state.get("source_fingerprint")))throw changed();
        Map<String,Object> result=map("patientId",source.getPatientId(),"sourceKind",ref.getKind().name(),"sourceId",ref.getId(),"managed",managed,"version",managed?state.get("lock_version"):0L,"sourceFingerprint",source.getFingerprint(),"nativeStatus",target.get("native_status"),"startWall",normalize(target.get("native_start_wall")),"endWall",normalize(target.get("native_end_wall")),"notifyWall",normalize(target.get("native_notify_wall")),"doctorUserId",source.getDoctorId(),"scheduleId",target.get("native_schedule_id"),"appointmentType",source.getValues().get("appointment_type"),"consultationMode",source.getValues().get("consultation_mode"),"attendance",managed?state.get("attendance"):"UNVERIFIED","attendanceOrigin",managed?state.get("attendance_origin"):"UNKNOWN","actualTimeKind",managed?state.get("actual_time_kind"):"UNKNOWN","actualAtUtc",managed?normalize(state.get("actual_at_utc")):null,"actualOffset",managed?state.get("actual_offset"):null,"automaticRecurrenceStopped",managed,"appointmentSystemTimeZone",null);
        Map<String,Object> fact=managed&&state.get("current_fact_event_id")!=null?read.facts.get(id(state.get("current_fact_event_id"))):null;
        if(managed) {
            if(state.get("current_fact_event_id")!=null&&fact==null)throw changed();
            if(fact!=null&&(number(fact.get("patient_id"))!=source.getPatientId()||number(fact.get("state_id"))!=number(state.get("id"))))throw changed();
            result.put("fact",normalize(fact));long owner=id(state.get("responsible_user_id"));result.put("responsibleUserId",owner);result.put("responsibleName",read.authority.displayName(owner));result.put("responsibleCurrent",read.authority.canAppointmentWrite(owner));result.put("dueAt",normalize(state.get("decision_due_at")));result.put("nextDecision",state.get("next_decision"));
            String current=String.valueOf(state.get("next_decision"));
            if(state.get("successor_kind")!=null) {
                SourceRef next=new SourceRef(SourceKind.valueOf(String.valueOf(state.get("successor_kind"))),id(state.get(ref.getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"successor_care_item_id":"successor_care_appointment_id")));
                NativeSource nextNative=read.sources.get(next);Map<String,Object> nextState=read.states.get(next);
                if(nextNative==null||!nextNative.isOrdinary()||!canRead(actor,nextNative,read.authority))current="CURRENT_TARGET_UNAVAILABLE";
                else {
                    result.put("successor",map("sourceKind",next.getKind().name(),"sourceId",next.getId(),"version",nextState==null?0L:nextState.get("lock_version"),"sourceFingerprint",nextNative.getFingerprint(),"startWall",normalize(nextNative.nativeTarget().get("native_start_wall")),"nativeStatus",nextNative.getValues().get("status")));
                    if(nextState==null||!Arrays.asList("OPEN","BOOKED").contains(nextNative.getValues().get("status"))||!"UNVERIFIED".equals(nextState.get("attendance"))||number(state.get("successor_bound_version"))!=number(nextState.get("lock_version"))||!nextNative.getFingerprint().equals(state.get("successor_bound_fingerprint"))||!nextNative.getFingerprint().equals(nextState.get("source_fingerprint")))current="NEEDS_DECISION";
                }
            }
            result.put("currentNextDecision",current);result.put("reminderAttempted",state.get("reminder_attempt_source_revision")!=null&&number(state.get("reminder_attempt_source_revision"))==number(state.get("source_revision")));
        }
        result.put("schemaStatus",availability.name());result.put("reminderAvailable",availability.isWriteAvailable(properties.isEnabled()));result.put("allowedCommands",availability.isWriteAvailable(properties.isEnabled())?allowed(actor,source,state,fact,read.authority):Collections.emptyList());return result;
    }
    /** 列表不搜索日志；单条详情只读取明确pointer的一行，旧revision绝不解释成本次成功。 */
    private boolean verifyReminderReference(SourceRef ref,Read read) {
        Map<String,Object> state=read.states.get(ref);if(state==null||state.get("reminder_delivery_log_id")==null)return false;
        if(!read.sources.get(ref).getFingerprint().equals(state.get("source_fingerprint")))throw changed();
        long log=id(state.get("reminder_delivery_log_id"));Map<String,Object> row=repo.reminderDeliveryLog(log);
        if(row==null||number(row.get("id"))!=log||number(row.get("patient_id"))!=number(state.get("patient_id"))||!REMINDER_EVENT.equals(row.get("event_type"))||!"DELIVERED".equals(row.get("delivery_status")))throw changed();
        try {
            id(row.get("recipient_user_id"));com.fasterxml.jackson.databind.JsonNode detail=reminderDetail("DELIVERED",String.valueOf(row.get("detail")));
            if(!Objects.equals(state.get("reminder_attempt_id"),detail.get("attemptId").asText())||number(state.get("id"))!=detail.get("stateId").longValue()||!ref.getKind().name().equals(detail.get("sourceKind").asText())||ref.getId()!=detail.get("sourceId").longValue()||number(state.get("reminder_attempt_source_revision"))!=detail.get("sourceRevision").longValue())throw changed();
        }catch(WorkflowException malformed){throw changed();}
        return true;
    }
    private Map<String,Object> reminderProjection(SourceRef ref,Read read,boolean verified) {
        Map<String,Object> state=read.states.get(ref);if(state==null)return null;
        boolean attempted=state.get("reminder_attempt_source_revision")!=null,current=attempted&&number(state.get("reminder_attempt_source_revision"))==number(state.get("source_revision"));
        Object marker=read.sources.get(ref).getNotifiedWall();boolean pointer=state.get("reminder_delivery_log_id")!=null;
        String status=marker!=null&&!pointer?"PRESERVED_LEGACY_MARKER":!current&&(attempted||marker!=null)?"PRIOR_ATTEMPT_REFERENCE":current&&verified&&marker!=null?"CURRENT_DELIVERED":current?"CURRENT_RESULT_UNVERIFIED":"NOT_ATTEMPTED";
        return map("status",status,"currentAttempt",current,"lastAttemptedAtUtc",normalize(state.get("reminder_attempted_at_utc")),"nativeNotifiedWall",normalize(marker),"exactReferenceVerified",verified);
    }
    private List<String> allowed(long actor,NativeSource source,Map<String,Object> state,Map<String,Object> fact,VisitFollowUpAuthorizationService.Snapshot auth) {
        List<String> commands=new ArrayList<>();if(!properties.isEnabled()||!auth.canAppointmentWrite(actor)||!source.isOrdinary())return commands;
        if(state==null){boolean legacy=source.getReference().getKind()==SourceKind.CARE_ITEM_APPOINTMENT;Map<String,Object> nativeTarget=source.nativeTarget();if(!Arrays.asList(legacy?"OPEN":"BOOKED",legacy?"DONE":"COMPLETED","NO_SHOW","CANCELLED").contains(source.getValues().get("status"))||nativeTarget.get("native_start_wall")==null)return commands;if(!legacy&&(nativeTarget.get("native_end_wall")==null||!((java.time.LocalDateTime)nativeTarget.get("native_end_wall")).isAfter((java.time.LocalDateTime)nativeTarget.get("native_start_wall"))))return commands;commands.add("TRACK_APPOINTMENT");return commands;}if(!source.getFingerprint().equals(state.get("source_fingerprint")))return commands;
        boolean selected=Objects.equals(source.getDoctorId(),actor)&&auth.isActiveDoctor(actor);
        for(String operation:Arrays.asList("RESCHEDULE","CANCEL","RECORD_ATTENDANCE","COMPLETE_SELECTED_APPOINTMENT","CORRECT_ATTENDANCE"))try {
            AppointmentFollowUpTransitions.decide(source.getReference().getKind().name(),String.valueOf(source.getValues().get("status")),String.valueOf(state.get("attendance")),String.valueOf(state.get("attendance_origin")),fact!=null,operation,"CORRECT_ATTENDANCE".equals(operation)?String.valueOf(state.get("attendance")):"ATTENDED",selected,fact!=null&&number(fact.get("actor_id"))==actor);commands.add(operation);
        }catch(WorkflowException denied){if(denied.getStatus()!=409)throw denied;}
        commands.add("SET_NEXT_DECISION");if(state.get("successor_kind")==null)commands.addAll(Arrays.asList("CREATE_NEXT","LINK_NEXT"));else commands.add("REPLACE_NEXT");return commands;
    }
    private static boolean canRead(long actor,NativeSource source,VisitFollowUpAuthorizationService.Snapshot auth) {return source!=null&&(auth.canAppointmentRead(actor)||source.getReference().getKind()==SourceKind.CARE_APPOINTMENT&&Objects.equals(source.getDoctorId(),actor)&&auth.isActiveDoctor(actor));}
    private static SourceRef sourceRef(Map<String,Object> row){return new SourceRef(number(row.get("kind_code"))==1?SourceKind.CARE_ITEM_APPOINTMENT:SourceKind.CARE_APPOINTMENT,id(row.get("source_id")));}
    private VisitFollowUpSchema.Availability requireStorage(){VisitFollowUpSchema.Availability availability=bridge.availability();if(!availability.isReadAvailable())throw new WorkflowException(503,"VISIT_FOLLOWUP_SCHEMA_NOT_READY","Appointment follow-up is not installed.");return availability;}
    private static void pageSize(int size){if(size<1||size>MAX_PAGE)throw invalid("Page size must be between 1 and 25.");}
    private static Map<String,Object> bounded(Map<String,Object> value){requireBytes(WorkflowData.json(value),RESPONSE_BYTES);return value;}
    private static WorkflowException changed(){return new WorkflowException(409,"CURRENT_SOURCE_UNAVAILABLE","The source or current access changed while reading. Refresh to compare.");}
    private static final class Read {
        final VisitFollowUpAuthorizationService.Snapshot authority;final Map<SourceRef,NativeSource> sources;final Map<SourceRef,Map<String,Object>> states;final Map<Long,Map<String,Object>> facts;
        Read(VisitFollowUpAuthorizationService.Snapshot authority,Map<SourceRef,NativeSource> sources,Map<SourceRef,Map<String,Object>> states,Map<Long,Map<String,Object>> facts){this.authority=authority;this.sources=sources;this.states=states;this.facts=facts;}
        String signature(){Map<String,Object> sourceHashes=new TreeMap<>(),stateRows=new TreeMap<>();sources.forEach((k,v)->sourceHashes.put(k.toString(),map("fingerprint",v.getFingerprint(),"ordinary",v.isOrdinary(),"notifiedWall",v.getNotifiedWall())));states.forEach((k,v)->stateRows.put(k.toString(),v));return canonical(map("authority",authority.signature(),"sources",sourceHashes,"states",stateRows,"facts",facts));}
    }
}
