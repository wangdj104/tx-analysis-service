package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class FamilyCareService {
    @Autowired private MedicalRecordReviewAccess medicalAccess;
    @Autowired private org.springframework.jdbc.datasource.DataSourceTransactionManager humanTransactions;
    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    @Autowired private org.familyhealthcare.service.visitquestion.VisitQuestionProjection questionProjection;
    @Autowired private org.familyhealthcare.service.visitquestion.VisitQuestionLegacyAdapter questions;
    @Autowired private org.familyhealthcare.service.familyassistance.FamilyAssistanceQueryService familyQueries;
    @Autowired private org.familyhealthcare.service.familyassistance.FamilyAssistanceSourceBridge familyAssistance;
    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard tracking;
    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationBoundaryService boundary;
    @Autowired private CareItemMapper items;
    @Autowired private DataScopeHelper scope;
    @Autowired private CareMembershipService membership;
    @Autowired private MedicationMapper medications;
    @Autowired private MedicationReminderMapper reminders;
    @Autowired private MedicalRecordMapper reports;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public List<CareItem> list(Long pid,String kind){scope.requirePatient(pid);QueryWrapper<CareItem>q=new QueryWrapper<CareItem>().eq("patient_id",pid);if("QUESTION".equals(kind))return (List<CareItem>)questionProjection.editingPage(scope.requireUserId(),pid,null,25).get("items");if(kind!=null)q.eq("kind",kind);else q.ne("kind","QUESTION");return projectReports(pid,tracking.legacyRead(pid).untrackedOrders(familyQueries.legacyItems(scope.requireUserId(),pid,(familyAssistance.appointmentReadStorageAvailable()?items.selectFollowUpItems(pid,kind,null):items.selectList(q.orderByDesc("event_at").orderByDesc("id"))))));}
    public CareItem get(Long id){if(questions.isQuestion(id))return questionProjection.locate(scope.requireUserId(),id);CareItem v; if(familyAssistance.appointmentReadStorageAvailable()){List<CareItem> projected=items.selectFollowUpItems(null,null,id);v=projected.isEmpty()?null:projected.get(0);}else v=items.selectById(id);if(v==null)throw new IllegalArgumentException("The record does not exist.");scope.requirePatient(v.getPatientId());List<CareItem> visible=tracking.legacyRead(v.getPatientId()).untrackedOrders(familyQueries.legacyItems(scope.requireUserId(),v.getPatientId(),Collections.singletonList(v)));if(visible.isEmpty())throw org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService.denied();return projectReports(v.getPatientId(),visible).get(0);}
    public Map<String,Object> questionPage(Long patient,String cursor,Integer limit){return org.familyhealthcare.service.visitquestion.VisitQuestionFailure.expose(()->questionProjection.editingPage(scope.requireUserId(),patient,cursor,limit));}
    public void finishQuestionRead(Long patient,Map<String,Object> page){org.familyhealthcare.service.visitquestion.VisitQuestionFailure.expose(()->{questionProjection.finishEditing(scope.requireUserId(),patient,(List<CareItem>)page.get("items"));return null;});}
    public CareItem publicProfile(Long pid){CareItem row=profile(pid);if(row==null)return null;List<CareItem> visible=familyQueries.legacyItems(scope.requireUserId(),pid,Collections.singletonList(row));return visible.isEmpty()?null:visible.get(0);}
    public CareItem profile(Long pid){return items.selectOne(new QueryWrapper<CareItem>().eq("patient_id",pid).eq("kind","PROFILE").last("limit 1"));}
    public Long coordinator(Long pid){CareItem p=profile(pid);Object id=p==null?null:p.getDetails().get("escalationUserId");return id==null||id.toString().isEmpty()?null:Long.valueOf(id.toString());}
    public int delayMinutes(Long pid){CareItem p=profile(pid);Object delay=p==null?null:p.getDetails().get("escalationMinutes");return delay==null?60:Math.max(15,Integer.parseInt(delay.toString()));}

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public CareItem save(CareItem v){
        if(questions.isQuestion(v))return org.familyhealthcare.service.visitquestion.VisitQuestionFailure.expose(()->questions.save(scope.requireUserId(),v));
        Patient patient=scope.requirePatient(v.getPatientId());if(patient==null)throw new IllegalArgumentException("SelectFamily Member");
        if(!Arrays.asList("APPOINTMENT","ORDER","SYMPTOM","QUESTION","HANDOVER","PROFILE").contains(v.getKind()))throw new IllegalArgumentException("recordtypeNonevalid");
        if(v.getTitle()==null||v.getTitle().trim().isEmpty()||v.getTitle().length()>200)throw new IllegalArgumentException("Please fillwrite200charactertowithin title");
        if(!membership.canAssign(v.getPatientId(),v.getAssignedUserId()))throw new IllegalArgumentException("responsible forpersonmustYesthis Family Member carecompletemember");
        if(v.getId()!=null&&!"ORDER".equals(v.getKind()))familyAssistance.requireLegacyItemWrite(scope.requireUserId(),v.getPatientId(),v.getId());
        CareItem old=v.getId()==null?null:get(v.getId());
        if(old!=null&&(!Objects.equals(old.getPatientId(),v.getPatientId())||!Objects.equals(old.getKind(),v.getKind())))throw new IllegalArgumentException("cannotchangerecordbelongFamily Member or type");
        if(old!=null&&"ORDER".equals(old.getKind()))throw new IllegalArgumentException("prescriptionchangePlease Addversionthis , keeporiginalsolution");
        if("PROFILE".equals(v.getKind())){CareItem existing=profile(v.getPatientId());v.setId(existing==null?null:existing.getId());old=existing;}
        v.setUserId(patient.getUserId());v.setActorId(scope.requireUserId());v.setActorName(CurrentUserUtil.getCurrentUsername());
        v.setStatus(old==null?("ORDER".equals(v.getKind())?"SCHEDULED":"OPEN"):old.getStatus());
        v.setCreatedAt(old==null?null:old.getCreatedAt());v.setUpdatedAt(LocalDateTime.now());
        v.setNotifiedAt(old==null?null:old.getNotifiedAt());v.setEscalatedAt(old==null?null:old.getEscalatedAt());
        Map<String,Object>d=v.getDetails();
        for(String key:Arrays.asList("reportId","appointmentId","escalationUserId"))if(d.get(key)!=null&&d.get(key).toString().trim().isEmpty())d.put(key,null);
        v.setDetails(d);
        if("APPOINTMENT".equals(v.getKind())){
            if(v.getEventAt()==null)throw new IllegalArgumentException("SelectappointmentTime");
            if(v.getNotifyAt()==null)v.setNotifyAt(v.getEventAt().minusDays(1));
            if(v.getNotifyAt().isAfter(v.getEventAt()))throw new IllegalArgumentException("ReminderTimecannotlater thanappointmentTime");
            if(d.get("nextAt")!=null&&!d.get("nextAt").toString().isEmpty()&&!LocalDateTime.parse(d.get("nextAt").toString().replace(' ','T')).isAfter(v.getEventAt()))throw new IllegalArgumentException("down timesfollow-up examinationshouldlater thanthis timesappointment");
            if(old!=null&&!Objects.equals(v.getEventAt(),old.getEventAt())){v.setNotifiedAt(null);v.setEscalatedAt(null);}
            if(d.get("reportId")!=null){MedicalRecord r=reports.selectById(Long.valueOf(d.get("reportId").toString()));if(r==null||!Objects.equals(r.getPatientId(),v.getPatientId()))throw new IllegalArgumentException("Reportdoes not belong tocurrentFamily Member");}
            if(d.get("reportId")!=null&&(old==null||!Objects.equals(String.valueOf(d.get("reportId")),String.valueOf(old.getDetails().get("reportId"))))) {
                long report=Long.parseLong(d.get("reportId").toString());jdbc.queryForList("SELECT id FROM medical_record WHERE id=? AND patient_id=? FOR UPDATE",Long.class,report,v.getPatientId());
                org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State state=medicalValidity.open().one(report);
                if(state==null||!Objects.equals(state.patientId,v.getPatientId())||!state.active())throw org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.changed();
            }
        }
        if("SYMPTOM".equals(v.getKind())){int severity=Integer.parseInt(String.valueOf(d.getOrDefault("severity",0)));if(severity<0||severity>10)throw new IllegalArgumentException("symptomlevelshouldin 0to 10between");if(v.getEventAt()==null)throw new IllegalArgumentException("SelectsymptomoccurTime");}
        if("QUESTION".equals(v.getKind())&&d.get("appointmentId")!=null){CareItem a=get(Long.valueOf(d.get("appointmentId").toString()));if(!"APPOINTMENT".equals(a.getKind())||!Objects.equals(a.getPatientId(),v.getPatientId()))throw new IllegalArgumentException("SelectcurrentFamily Member appointment");}
        if("PROFILE".equals(v.getKind())&&d.get("escalationUserId")!=null&&!membership.canAssign(v.getPatientId(),Long.valueOf(d.get("escalationUserId").toString())))throw new IllegalArgumentException("Selecthas valid follow-upfamily caregiver");
        if("ORDER".equals(v.getKind())){tracking.requireLegacyOrderWriteAllowed(scope.requireUserId(),v.getPatientId(),v.getId(),Long.parseLong(String.valueOf(v.getDetails().get("medicationId"))));validateOrder(v);}
        if(v.getId()==null)items.insert(v);else items.updateById(v);
        if("ORDER".equals(v.getKind()))activateIfDue(v,LocalDate.now());
        activity(v,"Create or Edit: "+v.getTitle());return v;
    }
    private List<CareItem> projectReports(Long patient,List<CareItem> rows) {
        LinkedHashSet<Long> ids=new LinkedHashSet<>();
        for(CareItem row:rows){Object report=row.getDetails().get("reportId");if(report!=null){row.setReportSourceStatus("SOURCE_UNAVAILABLE");try{long id=Long.parseLong(report.toString());ids.add(id);}catch(NumberFormatException ignored){}}}
        if(ids.isEmpty()||!reportMedicalAccess(patient))return rows;
        for(long id:ids)org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(id);
        Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> observed=reportObservations(patient,new ArrayList<>(ids));
        for(CareItem row:rows){Object report=row.getDetails().get("reportId");if(report!=null)try{
            org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation link=observed.get(Long.valueOf(report.toString()));
            if(link!=null&&link.metadataAvailable){String status=link.sourceStatus;if("CURRENT".equals(status)&&(link.nativeState.managed||link.sourceState!=null||link.doctorState!=null))status="UNKNOWN_NEEDS_RECHECK";row.setReportSourceStatus(status);}
        }catch(NumberFormatException ignored){}}
        return rows;
    }
    private boolean reportMedicalAccess(Long patient){try{scope.requirePatientAccess(patient,"MEDICAL",false);return true;}catch(IllegalStateException denied){return false;}}
    private void reportGraph(){medicalValidity.requireDataSource(jdbc.getDataSource());medicalAccess.requireDataSource(jdbc.getDataSource());if(humanTransactions==null||humanTransactions.getDataSource()!=jdbc.getDataSource())throw reportUnavailable();}
    private Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> reportObservations(Long patient,List<Long> ids) {
        reportGraph();ReportOwner owner=new ReportOwner(false);medicalValidity.open();Map<Long,MedicalRecord> headers=reportHeaders(patient,ids,owner);owner.verify();
        if(headers.isEmpty()){if(!reportMedicalAccess(patient)){owner.verify();return Collections.emptyMap();}owner.verify();return Collections.emptyMap();}
        Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> result;
        if(owner.actual&&!owner.readOnly) {
            result=reportSweep(patient,ids,headers,owner);
            if(!sameReportHeaders(headers,reportHeaders(patient,ids,owner)))throw reportChanged();
        } else {
            if(!reportAdmission(headers,owner.actor)){owner.verify();return Collections.emptyMap();}owner.verify();
            if(owner.actual) {
                org.springframework.transaction.support.TransactionTemplate current=new org.springframework.transaction.support.TransactionTemplate(humanTransactions);
                current.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);current.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);current.setReadOnly(true);
                try{result=current.execute(status->{ReportOwner inner=new ReportOwner(true);if(inner.holder==owner.holder||inner.connection==owner.connection)throw reportUnavailable();return stableReports(patient,ids,headers,inner);});}
                finally{owner.verify();}
            }else result=stableReports(patient,ids,headers,owner);
            if(!reportAdmission(headers,owner.actor)){owner.verify();return Collections.emptyMap();}
        }
        if(!reportMedicalAccess(patient)){owner.verify();return Collections.emptyMap();}owner.verify();
        if(result==null)throw reportUnavailable();
        for(org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation link:result.values())if(link!=null&&!link.metadataAvailable)return Collections.emptyMap();
        return result;
    }
    /** This E2 entrypoint exposes its exact permission-denial subtype as IllegalStateException; infrastructure failures are WorkflowException. */
    private boolean reportAdmission(Map<Long,MedicalRecord> headers,long actor){try{medicalAccess.requireReadAdmission(headers.values(),actor);return true;}catch(IllegalStateException denied){return false;}}
    private Map<Long,MedicalRecord> reportHeaders(Long patient,List<Long> ids,ReportOwner owner) {
        Map<Long,MedicalRecord> result=new LinkedHashMap<>();
        for(int start=0;start<ids.size();start+=100){List<Long> part=ids.subList(start,Math.min(start+100,ids.size()));List<Object> args=new ArrayList<>();args.add(patient);args.addAll(part);
            List<MedicalRecord> selected=jdbc.query("SELECT id,patient_id,user_id,content_version,verification_status FROM medical_record WHERE patient_id=? AND id IN ("+String.join(",",Collections.nCopies(part.size(),"?"))+") ORDER BY id LIMIT "+(part.size()+1),statement->{owner.verify();statement.setQueryTimeout(owner.actual&&owner.holder.hasTimeout()?Math.min(5,owner.holder.getTimeToLiveInSeconds()):5);for(int n=0;n<args.size();n++)statement.setObject(n+1,args.get(n));},(r,n)->{MedicalRecord header=new MedicalRecord();header.setId(r.getLong("id"));header.setPatientId(org.familyhealthcare.service.workflow.WorkflowData.nullableId(r,"patient_id"));header.setUserId(org.familyhealthcare.service.workflow.WorkflowData.nullableId(r,"user_id"));header.setContentVersion(org.familyhealthcare.service.workflow.WorkflowData.nullableId(r,"content_version"));header.setVerificationStatus(r.getString("verification_status"));return header;});
            for(MedicalRecord header:selected)if(!part.contains(header.getId())||!Objects.equals(patient,header.getPatientId())||result.put(header.getId(),header)!=null)throw reportChanged();
        }return result;
    }
    private static boolean sameReportHeader(MedicalRecord a,MedicalRecord b){return a!=null&&b!=null&&Objects.equals(a.getId(),b.getId())&&Objects.equals(a.getPatientId(),b.getPatientId())&&Objects.equals(a.getUserId(),b.getUserId())&&Objects.equals(a.getContentVersion(),b.getContentVersion())&&Objects.equals(a.getVerificationStatus(),b.getVerificationStatus());}
    private static boolean sameReportHeaders(Map<Long,MedicalRecord> a,Map<Long,MedicalRecord> b){if(!a.keySet().equals(b.keySet()))return false;for(long id:a.keySet())if(!sameReportHeader(a.get(id),b.get(id)))return false;return true;}
    private Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> reportSweep(Long patient,List<Long> inventory,Map<Long,MedicalRecord> headers,ReportOwner owner) {
        owner.verify();Map<Long,MedicalRecord> current=reportHeaders(patient,inventory,owner);owner.verify();
        for(long id:current.keySet())if(!sameReportHeader(headers.get(id),current.get(id)))throw reportChanged();
        Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> result=new LinkedHashMap<>();for(long id:inventory)result.put(id,null);
        List<Long> ids=new ArrayList<>(current.keySet());org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope medical=medicalValidity.open();
        for(int start=0;start<ids.size();start+=100){List<Long> part=ids.subList(start,Math.min(start+100,ids.size()));Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> batch=medical.humanLinkBatch(part);owner.verify();
            if(batch==null||!new HashSet<>(part).containsAll(batch.keySet()))throw reportChanged();
            for(Map.Entry<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> entry:batch.entrySet()){
                long id=entry.getKey();org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation link=entry.getValue();MedicalRecord header=headers.get(id);
                if(link==null||link.nativeState==null||link.nativeState.id!=id||!Objects.equals(header.getPatientId(),link.nativeState.patientId)||!Objects.equals(header.getUserId(),link.nativeState.ownerId)||!Objects.equals(header.getContentVersion(),link.nativeState.version)||!Objects.equals(header.getVerificationStatus(),link.nativeState.review))throw reportChanged();
                if(link.metadataAvailable&&(link.checkedAt==null||link.qualificationSemantics!=(owner.actual&&owner.isolation==java.sql.Connection.TRANSACTION_REPEATABLE_READ?org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT:org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics.CURRENT_CHECK)))throw reportUnavailable();result.put(id,link);
            }
        }owner.verify();return result;
    }
    private Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> stableReports(Long patient,List<Long> ids,Map<Long,MedicalRecord> headers,ReportOwner owner) {
        Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> previous=null;
        for(int pass=0;pass<3;pass++){Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> current=reportSweep(patient,ids,headers,owner);boolean equal=previous!=null&&previous.keySet().equals(current.keySet());if(equal)for(long id:current.keySet()){org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation a=previous.get(id),b=current.get(id);if(a==null?b!=null:!a.sameObservedFacts(b)){equal=false;break;}}if(equal)return current;previous=current;}return Collections.emptyMap();
    }
    private final class ReportOwner {
        final Thread thread=Thread.currentThread();final long actor=scope.requireUserId();
        final boolean actual=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),readOnly=org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        final org.springframework.jdbc.datasource.ConnectionHolder holder;final org.springframework.jdbc.datasource.SimpleConnectionHandle handle;final java.sql.Connection connection;final int isolation;
        ReportOwner(boolean privateCurrent) {
            reportGraph();Object resource=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if(!actual){holder=null;handle=null;connection=null;isolation=0;verify();return;}
            if(resource==null||resource.getClass()!=org.springframework.jdbc.datasource.ConnectionHolder.class)throw reportUnavailable();holder=(org.springframework.jdbc.datasource.ConnectionHolder)resource;
            if(holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=org.springframework.jdbc.datasource.SimpleConnectionHandle.class)throw reportUnavailable();handle=(org.springframework.jdbc.datasource.SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();
            try{isolation=connection.getTransactionIsolation();}catch(java.sql.SQLException|NullPointerException failure){throw reportUnavailable();}
            if(readOnly&&isolation!=java.sql.Connection.TRANSACTION_REPEATABLE_READ&&!(privateCurrent&&isolation==java.sql.Connection.TRANSACTION_READ_COMMITTED))throw reportUnavailable();verify();
        }
        void verify(){reportGraph();if(thread!=Thread.currentThread()||actor!=scope.requireUserId()||actual!=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()||readOnly!=org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw reportUnavailable();Object resource=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if(!actual){if(resource!=null||org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())throw reportUnavailable();return;}
            if(!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()||resource!=holder||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw reportUnavailable();
            try{if(connection==null||holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()||connection.getTransactionIsolation()!=isolation||isolation!=java.sql.Connection.TRANSACTION_READ_COMMITTED&&isolation!=java.sql.Connection.TRANSACTION_REPEATABLE_READ)throw reportUnavailable();}catch(java.sql.SQLException|IllegalStateException failure){throw reportUnavailable();}
        }
    }
    private static org.familyhealthcare.service.workflow.WorkflowException reportUnavailable(){return new org.familyhealthcare.service.workflow.WorkflowException(503,"CURRENT_RESULT_UNAVAILABLE","Current linked-source metadata could not be verified.");}
    private static org.familyhealthcare.service.workflow.WorkflowException reportChanged(){return new org.familyhealthcare.service.workflow.WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","Current linked-source metadata could not be verified.");}
    private void validateOrder(CareItem v){Map<String,Object>d=v.getDetails();
        if(!Boolean.TRUE.equals(d.get("confirmed")))throw new IllegalArgumentException("Please Confirmalready based onClinicianprescriptionverifysolution");
        Medication m=medications.selectById(Long.valueOf(String.valueOf(d.get("medicationId"))));if(m==null||!Objects.equals(m.getPatientId(),v.getPatientId()))throw new IllegalArgumentException("SelectcurrentFamily Member Medication");
        LocalDate start=LocalDate.parse(String.valueOf(d.get("startDate")));if(d.get("endDate")!=null&&!d.get("endDate").toString().isEmpty()&&LocalDate.parse(d.get("endDate").toString()).isBefore(start))throw new IllegalArgumentException("End DatecannotmorninginStart Date");
        String action=String.valueOf(d.getOrDefault("action","CHANGE"));if(!Arrays.asList("CHANGE","STOP").contains(action))throw new IllegalArgumentException("prescriptionActionsNonevalid");
        if(!"STOP".equals(action)){
            String unit=String.valueOf(d.getOrDefault("unit","tablet"));
            List<String> units=jdbc.queryForList("SELECT unit FROM medication_stock WHERE patient_id=? AND medication_id=?",String.class,v.getPatientId(),m.getId());
            if(unit.trim().isEmpty()||(!units.isEmpty()&&!unit.equals(units.get(0))))throw new IllegalArgumentException("prescriptiondosageUnitmust and MedicationInventoryUnitonecause, Please first verifyInventory");
            List<?> doses=(List<?>)d.get("doses");if(doses==null||doses.isEmpty())throw new IllegalArgumentException("Please fillwriteeach Daymedication intakeTime and dosage");
            Set<String>times=new HashSet<>();for(Object obj:doses){Map<?,?>dose=(Map<?,?>)obj;String time=LocalTime.parse(dose.get("time").toString()).toString();if(!times.add(time)||new BigDecimal(dose.get("quantity").toString()).signum()<=0)throw new IllegalArgumentException("Timecannotduplicate, dosagemustgreater than0");}
            if(!String.valueOf(d.getOrDefault("repeatDays","")).matches("[1-7](,[1-7])*"))throw new IllegalArgumentException("Selectduplicatestarperiod");
        }
        d.remove("reminderIds");v.setDetails(d);v.setEventAt(start.atStartOfDay());v.setNotifyAt(null);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void activateIfDue(CareItem v,LocalDate today){
        Map<String,Object> route=tracking.lockLegacyOrderTick(v.getId());
        if(route!=null&&Boolean.TRUE.equals(route.get("tracked"))){if(Boolean.TRUE.equals(route.get("owned")))tracking.applyTrackedOrderBoundary(route,Instant.now(),boundary,false);return;}
        v=items.selectById(v.getId());if(v==null)return;
        if(!"SCHEDULED".equals(v.getStatus())||v.getEventAt().toLocalDate().isAfter(today))return;
        CareItem locked=items.selectOne(new QueryWrapper<CareItem>().eq("id",v.getId()).last("FOR UPDATE"));
        if(locked==null||!"SCHEDULED".equals(locked.getStatus()))return;
        Map<String,Object>d=locked.getDetails();v=locked;Long mid=Long.valueOf(d.get("medicationId").toString());
        if(d.get("endDate")!=null&&!d.get("endDate").toString().isEmpty()&&LocalDate.parse(d.get("endDate").toString()).isBefore(today)){v.setStatus("STOPPED");items.updateById(v);return;}
        for(CareItem old:items.selectList(new QueryWrapper<CareItem>().eq("patient_id",v.getPatientId()).eq("kind","ORDER").eq("status","ACTIVE"))){if(String.valueOf(mid).equals(String.valueOf(old.getDetails().get("medicationId")))){old.setStatus("STOPPED");items.updateById(old);}}
        reminders.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MedicationReminder>().eq("patient_id",v.getPatientId()).eq("medication_id",mid).set("enabled",0));
        Medication med=medications.selectById(mid);boolean stop="STOP".equals(d.get("action"));med.setIsActive(stop?0:1);medications.updateById(med);
        List<Long>ids=new ArrayList<>();if(!stop)for(Object obj:(List<?>)d.get("doses")){Map<?,?>dose=(Map<?,?>)obj;MedicationReminder r=new MedicationReminder();r.setPatientId(v.getPatientId());r.setUserId(v.getUserId());r.setMedicationId(mid);r.setEnabled(1);r.setRemindTime(LocalTime.parse(dose.get("time").toString()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));r.setRepeatDays(d.get("repeatDays").toString());r.setDosage(dose.get("quantity")+" "+d.getOrDefault("unit","tablet"));r.setRemark("prescriptionversionthis  #"+v.getId());reminders.insert(r);ids.add(r.getId());}
        d.put("reminderIds",ids);v.setDetails(d);v.setStatus(stop?"STOPPED":"ACTIVE");items.updateById(v);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public CareItem action(Long id,String status,String note){return action(id,status,note,Collections.emptyMap());}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public CareItem action(Long id,String status,String note,Map<String,String> command){if(questions.isQuestion(id))return org.familyhealthcare.service.visitquestion.VisitQuestionFailure.expose(()->questions.action(scope.requireUserId(),id,status,note,command));CareItem discovered=items.selectById(id);if(discovered==null)throw new IllegalArgumentException("Record not found");
        if(!"ORDER".equals(discovered.getKind()))familyAssistance.requireLegacyItemWrite(scope.requireUserId(),discovered.getPatientId(),id);
        if("ORDER".equals(discovered.getKind()))tracking.requireLegacyOrderWriteAllowed(scope.requireUserId(),discovered.getPatientId(),id,Long.parseLong(String.valueOf(discovered.getDetails().get("medicationId"))));
        CareItem v=items.selectOne(new QueryWrapper<CareItem>().eq("id",id).last("FOR UPDATE"));
        if(v==null||!Objects.equals(discovered.getPatientId(),v.getPatientId())||!Objects.equals(discovered.getKind(),v.getKind()))throw new IllegalStateException("Record changed");scope.requirePatient(v.getPatientId());
        String previousStatus=v.getStatus();
        if("ORDER".equals(v.getKind())){if(!"CANCELLED".equals(status)||!"SCHEDULED".equals(v.getStatus()))throw new IllegalArgumentException("take effectprescriptionPlease throughAddchange or stop medicationprescriptionprocess");}
        else if(!Arrays.asList("OPEN","DONE","CANCELLED","ANSWERED","RESOLVED").contains(status))throw new IllegalArgumentException("Invalid status.");
        v.setStatus(status);v.setActorId(scope.requireUserId());v.setActorName(CurrentUserUtil.getCurrentUsername());
        Map<String,Object>d=v.getDetails();if(note!=null)d.put("answer",note);
        if("APPOINTMENT".equals(v.getKind())&&"DONE".equals(status)&&!"DONE".equals(previousStatus)&&d.get("nextAppointmentId")==null&&d.get("nextAt")!=null&&!d.get("nextAt").toString().isEmpty()){
            CareItem next=new CareItem();next.setPatientId(v.getPatientId());next.setKind("APPOINTMENT");next.setTitle(v.getTitle());next.setAssignedUserId(v.getAssignedUserId());next.setEventAt(LocalDateTime.parse(d.get("nextAt").toString().replace(' ','T')));
            Map<String,Object>details=new LinkedHashMap<>(d);details.remove("reportId");details.remove("nextAt");details.remove("nextAppointmentId");next.setDetails(details);save(next);d.put("nextAppointmentId",next.getId());
        }
        v.setDetails(d);if("OPEN".equals(status)){v.setNotifiedAt(null);v.setEscalatedAt(null);}items.updateById(v);activity(v,"Statuschangefor  "+status+(note==null?"":": "+note));return v;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void delete(Long id){delete(id,Collections.emptyMap());}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public boolean delete(Long id,Map<String,String> command){if(questions.isQuestion(id))return org.familyhealthcare.service.visitquestion.VisitQuestionFailure.expose(()->questions.delete(scope.requireUserId(),id,command));CareItem candidate=items.selectById(id);if(candidate==null)throw new IllegalArgumentException("Record not found");if(!"ORDER".equals(candidate.getKind()))familyAssistance.requireLegacyItemWrite(scope.requireUserId(),candidate.getPatientId(),id);CareItem v=get(id);if("ORDER".equals(v.getKind()))tracking.requireLegacyOrderWriteAllowed(scope.requireUserId(),v.getPatientId(),id,Long.parseLong(String.valueOf(v.getDetails().get("medicationId"))));if("ORDER".equals(v.getKind()))throw new IllegalArgumentException("prescriptionhistorycannotDelete, Please Cancelnot take effectversionthis  or Addstop medicationprescription");items.deleteById(id);activity(v,"Delete: "+v.getTitle());return false;}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void endOrderIfDue(Long order,LocalDate today){Map<String,Object> route=tracking.lockLegacyOrderTick(order);if(route!=null&&Boolean.TRUE.equals(route.get("tracked"))){if(Boolean.TRUE.equals(route.get("owned")))tracking.applyTrackedOrderBoundary(route,Instant.now(),boundary,true);return;}CareItem current=items.selectOne(new QueryWrapper<CareItem>().eq("id",order).last("FOR UPDATE"));if(current==null||!"ORDER".equals(current.getKind())||!"ACTIVE".equals(current.getStatus()))return;Object end=current.getDetails().get("endDate");if(end==null||end.toString().isEmpty()||!LocalDate.parse(end.toString()).isBefore(today))return;List<?> ids=(List<?>)current.getDetails().getOrDefault("reminderIds",Collections.emptyList());if(!ids.isEmpty())reminders.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MedicationReminder>().in("id",ids).eq("patient_id",current.getPatientId()).set("enabled",0));current.setStatus("STOPPED");items.updateById(current);}
    public void activity(CareItem source,String title){if(org.familyhealthcare.service.familyassistance.FamilyAssistanceQueryService.restricted(source.getDetails()))return;CareItem a=new CareItem();a.setPatientId(source.getPatientId());a.setUserId(source.getUserId());a.setKind("ACTIVITY");a.setTitle(title.length()>200?title.substring(0,200):title);a.setStatus("DONE");a.setActorId(CurrentUserUtil.getCurrentUserId());a.setActorName(CurrentUserUtil.getCurrentUsername());a.setEventAt(LocalDateTime.now());Map<String,Object>d=new HashMap<>();d.put("sourceId",source.getId());a.setDetails(d);items.insert(a);}
}
