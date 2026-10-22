package org.familyhealthcare.service;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/** A consultation invitation grants access to that conversation, not the patient's full record. */
@Service
public class ConsultationService {
    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    @Autowired private org.familyhealthcare.service.MedicalRecordReviewAccess medicalAccess;
    @Autowired private org.springframework.jdbc.datasource.DataSourceTransactionManager humanTransactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataScopeHelper scope;
    @Autowired private CareJourneyService journey;

    private static final String SELECT_SESSION = "SELECT c.*,p.user_id AS patient_owner_id,p.name AS patient_name,"
            + "COALESCE(NULLIF(u.real_name,''),u.username) AS doctor_name,"
            + "COALESCE((SELECT MAX(m.sent_at) FROM consultation_message m WHERE m.consultation_id=c.id),c.started_at) AS last_message_at "
            + "FROM consultation c JOIN patient p ON p.id=c.patient_id LEFT JOIN sys_user u ON u.id=c.doctor_user_id "
            + "WHERE COALESCE(p.deleted,0)=0";

    public List<Map<String,Object>> inbox(String status) {
        Long uid = userId();
        List<Object> args = new ArrayList<>(Arrays.asList(uid, uid, uid, uid));
        String sql = SELECT_SESSION + " AND (c.doctor_user_id=? OR p.user_id=? OR c.created_by=? OR EXISTS "
                + "(SELECT 1 FROM consultation_participant cp WHERE cp.consultation_id=c.id AND cp.user_id=?))";
        if (status != null && !status.trim().isEmpty()) {
            String value = status.trim().toUpperCase(Locale.ROOT);
            if (!Arrays.asList("OPEN", "CLOSED").contains(value)) throw new IllegalArgumentException("Invalid consultation status.");
            sql += " AND c.status=?";
            args.add(value);
        }
        return visibleRows(jdbc.queryForList(sql + " ORDER BY CASE WHEN c.status='OPEN' THEN 0 ELSE 1 END,last_message_at DESC,c.id DESC LIMIT 200", args.toArray()));
    }

    public List<Map<String,Object>> list(Long patientId) {
        List<Map<String,Object>> rows = visibleRows(jdbc.queryForList(SELECT_SESSION + " AND c.patient_id=? ORDER BY c.started_at DESC,c.id DESC", patientId));
        if (rows.isEmpty()) journey.requireRead(patientId, "CONSULTATION");
        return rows;
    }

    private List<Map<String,Object>> visibleRows(List<Map<String,Object>> rows) {
        List<Map<String,Object>> visible = new ArrayList<>();
        for (Map<String,Object> row : rows) {
            try { requireAccess(row, false); }
            catch (IllegalStateException denied) { continue; }
            addSessionMetadata(row);
            visible.add(row);
        }
        return visible;
    }

    public List<Map<String,Object>> invitees(Long patientId) {
        journey.requireWrite(patientId, "CONSULTATION");
        return jdbc.queryForList("SELECT u.id AS user_id,COALESCE(NULLIF(u.real_name,''),u.username) AS real_name,m.relation_name FROM care_member m JOIN sys_user u ON u.id=m.user_id WHERE m.patient_id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 ORDER BY real_name", patientId);
    }

    @Transactional
    public Map<String,Object> start(Map<String,Object> body) {
        Long patientId = requiredId(body, "patientId");
        journey.requireWrite(patientId, "CONSULTATION");
        Long doctor = requiredId(body, "doctorUserId");
        Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 AND r.role_code='doctor' AND r.status=1", Integer.class, doctor);
        if (valid == null || valid == 0) throw new IllegalArgumentException("Select an active doctor for the consultation.");
        String mode = value(body, "mode", "TEXT").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("TEXT", "VOICE", "VIDEO").contains(mode)) throw new IllegalArgumentException("Unsupported consultation mode.");
        String visibility = value(body, "familyVisibility", "VISIBLE").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("VISIBLE", "PRIVATE").contains(visibility)) throw new IllegalArgumentException("Invalid family visibility.");
        String symptom = checked(body, "symptom", 500, true);
        String duration = checked(body, "durationText", 100, false);
        String history = checked(body, "medicalHistory", 10000, false);
        Long owner = jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?", Long.class, patientId);
        long id = insert("INSERT INTO consultation(patient_id,doctor_user_id,mode,status,symptom,duration_text,medical_history,family_visibility,created_by) VALUES(?,?,?,'OPEN',?,?,?,?,?)", patientId, doctor, mode, symptom, duration, history, visibility, userId());
        Set<Long> participants = new LinkedHashSet<>(Arrays.asList(userId(), owner, doctor));
        if ("VISIBLE".equals(visibility)) {
            Set<Long> family = new HashSet<>(jdbc.queryForList("SELECT user_id FROM care_member WHERE patient_id=?", Long.class, patientId));
            Object invitees = body.get("participantUserIds");
            if (invitees instanceof Collection) for (Object raw : (Collection<?>) invitees) {
                Long invited = Long.valueOf(raw.toString());
                if (family.contains(invited)) participants.add(invited);
            }
        }
        participants.remove(null);
        for (Long uid : participants) jdbc.update("INSERT IGNORE INTO consultation_participant(consultation_id,user_id,participant_role) VALUES(?,?,?)", id, uid, Objects.equals(uid, doctor) ? "DOCTOR" : Objects.equals(uid, owner) ? "PATIENT" : "CARE_TEAM");
        return detail(id);
    }

    public Map<String,Object> detail(Long id) {
        Map<String,Object> session = session(id, false);
        requireAccess(session, false);
        addSessionMetadata(session);
        List<Map<String,Object>> messages = jdbc.queryForList("SELECT m.*,COALESCE(NULLIF(u.real_name,''),u.username) AS sender_name FROM consultation_message m LEFT JOIN sys_user u ON u.id=m.sender_user_id WHERE m.consultation_id=? ORDER BY m.sent_at,m.id", id);
        List<Long> sourceIds=new ArrayList<>();for(Map<String,Object> message:messages){if(message.get("attachment_record_id")!=null)sourceIds.add(number(message.get("attachment_record_id")));loadAttachmentMetadata(message,number(session.get("patient_id")));}
        SourceFrame frame=sourceObservations(number(session.get("patient_id")),sourceIds);
        for(Map<String,Object> message:messages)projectAttachmentMetadata(message,frame);
        session.put("messages", messages);
        return session;
    }

    @Transactional
    public Map<String,Object> message(Long id, Map<String,Object> body) {
        Map<String,Object> session = session(id, true);
        requireAccess(session, true);
        requireOpen(session);
        String type = value(body, "messageType", "TEXT").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("TEXT", "IMAGE", "FILE", "VOICE").contains(type)) throw new IllegalArgumentException("Invalid message type.");
        Long recordId = optionalId(body, "attachmentRecordId");
        String content = checked(body, "content", 10000, recordId == null);
        Long patientId = number(session.get("patient_id"));
        if (recordId != null) {
            scope.requirePatientAccess(patientId,"MEDICAL",false);
            journey.requireRead(patientId, "MEDICAL");
            if (one("SELECT id FROM medical_record WHERE id=? AND patient_id=? AND "+medicalValidity.open().activeSql("medical_record.")+" FOR UPDATE", recordId, patientId) == null)
                throw new IllegalArgumentException("The attachment does not belong to this patient.");
        }
        if (("IMAGE".equals(type) || "FILE".equals(type)) && recordId == null)
            throw new IllegalArgumentException("Select a patient medical record to attach.");
        long messageId = insert("INSERT INTO consultation_message(consultation_id,sender_user_id,message_type,content,attachment_record_id) VALUES(?,?,?,?,?)", id, userId(), type, content, recordId);
        Map<String,Object> result = one("SELECT m.*,COALESCE(NULLIF(u.real_name,''),u.username) AS sender_name FROM consultation_message m LEFT JOIN sys_user u ON u.id=m.sender_user_id WHERE m.id=?", messageId);
        addAttachmentMetadata(result, patientId);
        return result;
    }

    public List<Map<String,Object>> recordOptions(Long id) {
        Map<String,Object> session = session(id, false);
        requireAccess(session, false);
        Long patientId = number(session.get("patient_id"));
        try { scope.requirePatientAccess(patientId,"MEDICAL",false); journey.requireRead(patientId, "MEDICAL"); } catch (IllegalStateException denied) { return Collections.emptyList(); }
        org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope medical=medicalValidity.open();
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,record_date,record_type,hospital_name,content_version AS contentVersion,verification_status AS verificationStatus FROM medical_record WHERE patient_id=? AND "+medical.activeSql("medical_record.")+" ORDER BY record_date DESC,id DESC LIMIT 100",patientId);List<Long> sourceIds=new ArrayList<>();for(Map<String,Object> row:rows)sourceIds.add(number(row.get("id")));SourceFrame frame=sourceObservations(patientId,sourceIds);
        for(Map<String,Object> row:rows){org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation link=frame.links.get(number(row.get("id")));if(link!=null){if(!Objects.equals(number(row.get("contentVersion")),link.nativeState.version)||!Objects.equals(row.get("verificationStatus"),link.nativeState.review))throw sourceChanged();row.put("managed",link.nativeState.managed);row.put("validity",link.nativeState.validity);}row.put("sourceStatus",sourceStatus(frame,link,false));}return rows;
    }

    public Map<String,Object> attachment(Long id, Long attachmentId) {
        Map<String,Object> session = session(id, false);
        requireAccess(session, false);
        Map<String,Object> result = one("SELECT a.id,a.file_name,a.file_type,a.file_content FROM medical_record_attachment a JOIN medical_record r ON r.id=a.record_id WHERE a.id=? AND r.patient_id=? AND EXISTS (SELECT 1 FROM consultation_message m WHERE m.consultation_id=? AND m.attachment_record_id=r.id)", attachmentId, session.get("patient_id"), id);
        if (result == null || result.get("file_content") == null || result.get("file_content").toString().trim().isEmpty())
            throw new IllegalArgumentException("The shared attachment is not available. Ask the sender to upload it again.");
        return result;
    }

    public List<Map<String,Object>> signals(Long id, Long afterId) {
        Map<String,Object> session = session(id, false);
        requireAccess(session, true);
        if (!"OPEN".equals(session.get("status"))) return Collections.emptyList();
        return jdbc.queryForList("SELECT * FROM consultation_signal WHERE consultation_id=? AND target_user_id=? AND id>? ORDER BY id LIMIT 200", id, userId(), afterId == null ? 0 : Math.max(0, afterId));
    }

    @Transactional
    public Map<String,Object> signal(Long id, Map<String,Object> body) {
        Map<String,Object> session = session(id, true);
        requireAccess(session, true);
        requireOpen(session);
        Long target = requiredId(body, "targetUserId");
        if (Objects.equals(target, userId()) || !participant(id, target)) throw new IllegalArgumentException("Signal target is not another consultation participant.");
        String type = value(body, "signalType", "").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("OFFER", "ANSWER", "ICE", "HANGUP").contains(type)) throw new IllegalArgumentException("Invalid signal type.");
        String payload = com.alibaba.fastjson2.JSON.toJSONString(body.get("payload"));
        if (payload.length() > 100000) throw new IllegalArgumentException("The call signal is too large.");
        long signalId = insert("INSERT INTO consultation_signal(consultation_id,sender_user_id,target_user_id,signal_type,payload_json) VALUES(?,?,?,?,?)", id, userId(), target, type, payload);
        return one("SELECT * FROM consultation_signal WHERE id=?", signalId);
    }

    @Transactional
    public Map<String,Object> close(Long id, Map<String,Object> body) {
        Map<String,Object> session = session(id, true);
        requireAccess(session, true);
        if (!canClose(session)) throw new IllegalStateException("Only the patient, initiating caregiver, or selected doctor can close this consultation.");
        if (!"OPEN".equals(session.get("status"))) return detail(id);
        String advice = checked(body, "transferAdvice", 1000, false);
        if (advice != null && !CurrentUserUtil.hasRole("doctor") && !CurrentUserUtil.isAdmin())
            throw new IllegalStateException("Only a doctor can provide referral advice.");
        jdbc.update("UPDATE consultation SET status='CLOSED',ended_at=NOW(),transfer_advice=? WHERE id=?", advice, id);
        // The shared timeline must not expose private symptom text. The full transcript stays behind consultation access checks.
        String summary = "PRIVATE".equals(session.get("family_visibility")) ? null : String.valueOf(session.get("symptom"));
        long eventId = insert("INSERT INTO health_event(user_id,patient_id,event_date,event_time,event_type,title,summary,source_type,source_id,status) VALUES(?,?,CURRENT_DATE,?,'CONSULTATION','Remote consultation',?,'CONSULTATION',?,'RECORDED')", session.get("patient_owner_id"), session.get("patient_id"), java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), summary, id);
        jdbc.update("UPDATE consultation SET archived_event_id=? WHERE id=?", eventId, id);
        jdbc.update("DELETE FROM consultation_signal WHERE consultation_id=?", id);
        return detail(id);
    }

    private Map<String,Object> session(Long id, boolean lock) {
        // Serialize sends and closing to prevent messages being appended after an archive is finalized.
        if (lock && one("SELECT id FROM consultation WHERE id=? FOR UPDATE", id) == null) throw new IllegalArgumentException("Consultation not found.");
        Map<String,Object> row = one(SELECT_SESSION + " AND c.id=?", id);
        if (row == null) throw new IllegalArgumentException("Consultation not found.");
        return row;
    }

    private void requireAccess(Map<String,Object> session, boolean participating) {
        Long uid = userId();
        if (CurrentUserUtil.isAdmin() || Objects.equals(uid, number(session.get("patient_owner_id")))) return;
        if (Objects.equals(uid, number(session.get("doctor_user_id"))) && CurrentUserUtil.hasRole("doctor")) return;
        journey.requireRead(number(session.get("patient_id")), "CONSULTATION");
        if ("PRIVATE".equals(session.get("family_visibility")) && !Objects.equals(uid, number(session.get("created_by"))))
            throw new IllegalStateException("This consultation is private and is not visible to family caregivers.");
        if (participating && !participant(number(session.get("id")), uid))
            throw new IllegalStateException("Only invited consultation participants can send messages or join calls.");
    }

    private void addSessionMetadata(Map<String,Object> session) {
        boolean canParticipate;
        try { requireAccess(session, true); canParticipate = true; } catch (IllegalStateException denied) { canParticipate = false; }
        session.put("can_message", canParticipate && "OPEN".equals(session.get("status")));
        session.put("can_close", canParticipate && canClose(session) && "OPEN".equals(session.get("status")));
        session.put("participants", jdbc.queryForList("SELECT cp.*,COALESCE(NULLIF(u.real_name,''),u.username) AS real_name FROM consultation_participant cp LEFT JOIN sys_user u ON u.id=cp.user_id WHERE cp.consultation_id=?", session.get("id")));
    }

    private boolean canClose(Map<String,Object> session) {
        Long uid = userId();
        return CurrentUserUtil.isAdmin() || Objects.equals(uid, number(session.get("patient_owner_id"))) || Objects.equals(uid, number(session.get("created_by"))) || Objects.equals(uid, number(session.get("doctor_user_id"))) && CurrentUserUtil.hasRole("doctor");
    }

    private boolean canReadMedicalStatus(Long patientId){try{scope.requirePatientAccess(patientId,"MEDICAL",false);journey.requireRead(patientId,"MEDICAL");return true;}catch(IllegalStateException denied){return false;}}
    private void addAttachmentMetadata(Map<String,Object> message, Long patientId) {
        loadAttachmentMetadata(message,patientId);if(message.get("attachment_record_id")==null)return;
        SourceFrame frame=sourceObservations(patientId,Collections.singleton(number(message.get("attachment_record_id"))));projectAttachmentMetadata(message,frame);
    }
    private void loadAttachmentMetadata(Map<String,Object> message,Long patientId) {
        if (message.get("attachment_record_id") == null) return;
        Map<String,Object> record = one("SELECT id,record_date,record_type,hospital_name FROM medical_record WHERE id=? AND patient_id=?", message.get("attachment_record_id"), patientId);
        if(record!=null)record.put("referenceStatus","SOURCE_UNAVAILABLE");
        if (record != null) record.put("attachments", jdbc.queryForList("SELECT id,file_name,file_type FROM medical_record_attachment WHERE record_id=?", record.get("id")));
        message.put("attachment_record", record);
    }
    @SuppressWarnings("unchecked")
    private void projectAttachmentMetadata(Map<String,Object> message,SourceFrame frame) {
        Map<String,Object> record=(Map<String,Object>)message.get("attachment_record");if(record==null)return;
        org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation link=frame.links.get(number(record.get("id")));if(link!=null)sourceFields(record,link);record.put("referenceStatus",sourceStatus(frame,link,true));
    }

    private static final class SourceFrame {
        final Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> links;final boolean released;
        SourceFrame(Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> links,boolean released){this.links=links;this.released=released;}
        static SourceFrame empty(){return new SourceFrame(Collections.emptyMap(),false);}
    }
    private static void sourceFields(Map<String,Object> row,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation link){row.put("managed",link.nativeState.managed);row.put("validity",link.nativeState.validity);row.put("verificationStatus",link.nativeState.review);row.put("contentVersion",link.nativeState.version);}
    private static String sourceStatus(SourceFrame frame,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation link,boolean reference){if(!frame.released||link==null||!link.metadataAvailable)return "SOURCE_UNAVAILABLE";String status=link.sourceStatus;if(reference&&"CURRENT".equals(status)&&(link.nativeState.managed||link.sourceState!=null||link.doctorState!=null))status="UNKNOWN_NEEDS_RECHECK";return status;}
    private void sourceGraph(){medicalValidity.requireDataSource(jdbc.getDataSource());medicalAccess.requireDataSource(jdbc.getDataSource());if(humanTransactions==null||humanTransactions.getDataSource()!=jdbc.getDataSource())throw sourceUnavailable();}
    private SourceFrame sourceObservations(Long patient,Collection<Long> input) {
        List<Long> ids=new ArrayList<>(new LinkedHashSet<>(input));if(ids.isEmpty()||!canReadMedicalStatus(patient))return SourceFrame.empty();for(long id:ids)org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(id);
        sourceGraph();SourceOwner owner=new SourceOwner(false);medicalValidity.open();Map<Long,MedicalRecord> headers=sourceHeaders(patient,ids,owner);owner.verify();
        if(headers.isEmpty()){if(!canReadMedicalStatus(patient)){owner.verify();return SourceFrame.empty();}owner.verify();return SourceFrame.empty();}
        SourceFrame result;
        if(owner.actual&&!owner.readOnly) {
            result=new SourceFrame(sourceSweep(patient,ids,headers,owner),true);
            if(!sameSourceHeaders(headers,sourceHeaders(patient,ids,owner)))throw sourceChanged();
        } else {
            if(!sourceAdmission(headers,owner.actor)){owner.verify();return SourceFrame.empty();}owner.verify();
            if(owner.actual) {
                org.springframework.transaction.support.TransactionTemplate current=new org.springframework.transaction.support.TransactionTemplate(humanTransactions);
                current.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);current.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);current.setReadOnly(true);
                try{result=current.execute(status->{SourceOwner inner=new SourceOwner(true);if(inner.holder==owner.holder||inner.connection==owner.connection)throw sourceUnavailable();return stableSources(patient,ids,headers,inner);});}
                finally{owner.verify();}
            }else result=stableSources(patient,ids,headers,owner);
            if(!sourceAdmission(headers,owner.actor)){owner.verify();return SourceFrame.empty();}
        }
        if(!canReadMedicalStatus(patient)){owner.verify();return SourceFrame.empty();}owner.verify();
        if(result==null)throw sourceUnavailable();
        for(org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation link:result.links.values())if(link!=null&&!link.metadataAvailable)return new SourceFrame(result.links,false);
        return result;
    }
    /** This E2 entrypoint exposes its exact permission-denial subtype as IllegalStateException; infrastructure failures are WorkflowException. */
    private boolean sourceAdmission(Map<Long,MedicalRecord> headers,long actor){try{medicalAccess.requireReadAdmission(headers.values(),actor);return true;}catch(IllegalStateException denied){return false;}}
    private Map<Long,MedicalRecord> sourceHeaders(Long patient,List<Long> ids,SourceOwner owner) {
        Map<Long,MedicalRecord> result=new LinkedHashMap<>();
        for(int start=0;start<ids.size();start+=100){List<Long> part=ids.subList(start,Math.min(start+100,ids.size()));List<Object> args=new ArrayList<>();args.add(patient);args.addAll(part);
            List<MedicalRecord> selected=jdbc.query("SELECT id,patient_id,user_id,content_version,verification_status FROM medical_record WHERE patient_id=? AND id IN ("+String.join(",",Collections.nCopies(part.size(),"?"))+") ORDER BY id LIMIT "+(part.size()+1),statement->{owner.verify();statement.setQueryTimeout(owner.actual&&owner.holder.hasTimeout()?Math.min(5,owner.holder.getTimeToLiveInSeconds()):5);for(int n=0;n<args.size();n++)statement.setObject(n+1,args.get(n));},(r,n)->{MedicalRecord header=new MedicalRecord();header.setId(r.getLong("id"));header.setPatientId(org.familyhealthcare.service.workflow.WorkflowData.nullableId(r,"patient_id"));header.setUserId(org.familyhealthcare.service.workflow.WorkflowData.nullableId(r,"user_id"));header.setContentVersion(org.familyhealthcare.service.workflow.WorkflowData.nullableId(r,"content_version"));header.setVerificationStatus(r.getString("verification_status"));return header;});
            for(MedicalRecord header:selected)if(!part.contains(header.getId())||!Objects.equals(patient,header.getPatientId())||result.put(header.getId(),header)!=null)throw sourceChanged();
        }return result;
    }
    private static boolean sameSourceHeader(MedicalRecord a,MedicalRecord b){return a!=null&&b!=null&&Objects.equals(a.getId(),b.getId())&&Objects.equals(a.getPatientId(),b.getPatientId())&&Objects.equals(a.getUserId(),b.getUserId())&&Objects.equals(a.getContentVersion(),b.getContentVersion())&&Objects.equals(a.getVerificationStatus(),b.getVerificationStatus());}
    private static boolean sameSourceHeaders(Map<Long,MedicalRecord> a,Map<Long,MedicalRecord> b){if(!a.keySet().equals(b.keySet()))return false;for(long id:a.keySet())if(!sameSourceHeader(a.get(id),b.get(id)))return false;return true;}
    private Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> sourceSweep(Long patient,List<Long> inventory,Map<Long,MedicalRecord> headers,SourceOwner owner) {
        owner.verify();Map<Long,MedicalRecord> current=sourceHeaders(patient,inventory,owner);owner.verify();
        for(long id:current.keySet())if(!sameSourceHeader(headers.get(id),current.get(id)))throw sourceChanged();
        Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> result=new LinkedHashMap<>();for(long id:inventory)result.put(id,null);
        List<Long> ids=new ArrayList<>(current.keySet());org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope medical=medicalValidity.open();
        for(int start=0;start<ids.size();start+=100){List<Long> part=ids.subList(start,Math.min(start+100,ids.size()));Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> batch=medical.humanLinkBatch(part);owner.verify();
            if(batch==null||!new HashSet<>(part).containsAll(batch.keySet()))throw sourceChanged();
            for(Map.Entry<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> entry:batch.entrySet()){
                long id=entry.getKey();org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation link=entry.getValue();MedicalRecord header=headers.get(id);
                if(link==null||link.nativeState==null||link.nativeState.id!=id||!Objects.equals(header.getPatientId(),link.nativeState.patientId)||!Objects.equals(header.getUserId(),link.nativeState.ownerId)||!Objects.equals(header.getContentVersion(),link.nativeState.version)||!Objects.equals(header.getVerificationStatus(),link.nativeState.review))throw sourceChanged();
                if(link.metadataAvailable&&(link.checkedAt==null||link.qualificationSemantics!=(owner.actual&&owner.isolation==java.sql.Connection.TRANSACTION_REPEATABLE_READ?org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT:org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics.CURRENT_CHECK)))throw sourceUnavailable();result.put(id,link);
            }
        }owner.verify();return result;
    }
    private SourceFrame stableSources(Long patient,List<Long> ids,Map<Long,MedicalRecord> headers,SourceOwner owner) {
        Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> previous=null;
        for(int pass=0;pass<3;pass++){Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation> current=sourceSweep(patient,ids,headers,owner);boolean equal=previous!=null&&previous.keySet().equals(current.keySet());if(equal)for(long id:current.keySet()){org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation a=previous.get(id),b=current.get(id);if(a==null?b!=null:!a.sameObservedFacts(b)){equal=false;break;}}if(equal)return new SourceFrame(current,true);previous=current;}return new SourceFrame(previous,false);
    }
    private final class SourceOwner {
        final Thread thread=Thread.currentThread();final long actor=scope.requireUserId();
        final boolean actual=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),readOnly=org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        final org.springframework.jdbc.datasource.ConnectionHolder holder;final org.springframework.jdbc.datasource.SimpleConnectionHandle handle;final java.sql.Connection connection;final int isolation;
        SourceOwner(boolean privateCurrent) {
            sourceGraph();Object resource=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if(!actual){holder=null;handle=null;connection=null;isolation=0;verify();return;}
            if(resource==null||resource.getClass()!=org.springframework.jdbc.datasource.ConnectionHolder.class)throw sourceUnavailable();holder=(org.springframework.jdbc.datasource.ConnectionHolder)resource;
            if(holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=org.springframework.jdbc.datasource.SimpleConnectionHandle.class)throw sourceUnavailable();handle=(org.springframework.jdbc.datasource.SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();
            try{isolation=connection.getTransactionIsolation();}catch(java.sql.SQLException|NullPointerException failure){throw sourceUnavailable();}
            if(readOnly&&isolation!=java.sql.Connection.TRANSACTION_REPEATABLE_READ&&!(privateCurrent&&isolation==java.sql.Connection.TRANSACTION_READ_COMMITTED))throw sourceUnavailable();verify();
        }
        void verify(){sourceGraph();if(thread!=Thread.currentThread()||actor!=scope.requireUserId()||actual!=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()||readOnly!=org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw sourceUnavailable();Object resource=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if(!actual){if(resource!=null||org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())throw sourceUnavailable();return;}
            if(!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()||resource!=holder||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw sourceUnavailable();
            try{if(connection==null||holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()||connection.getTransactionIsolation()!=isolation||isolation!=java.sql.Connection.TRANSACTION_READ_COMMITTED&&isolation!=java.sql.Connection.TRANSACTION_REPEATABLE_READ)throw sourceUnavailable();}catch(java.sql.SQLException|IllegalStateException failure){throw sourceUnavailable();}
        }
    }
    private static org.familyhealthcare.service.workflow.WorkflowException sourceUnavailable(){return new org.familyhealthcare.service.workflow.WorkflowException(503,"CURRENT_RESULT_UNAVAILABLE","Current linked-source metadata could not be verified.");}
    private static org.familyhealthcare.service.workflow.WorkflowException sourceChanged(){return new org.familyhealthcare.service.workflow.WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","Current linked-source metadata could not be verified.");}

    private boolean participant(Long id, Long uid) { return jdbc.queryForObject("SELECT COUNT(*) FROM consultation_participant WHERE consultation_id=? AND user_id=?", Integer.class, id, uid) > 0; }
    private void requireOpen(Map<String,Object> session) { if (!"OPEN".equals(session.get("status"))) throw new IllegalStateException("Consultation is closed."); }
    private Long userId() { Long uid = CurrentUserUtil.getCurrentUserId(); if (uid == null) throw new IllegalStateException("Authentication is required."); return uid; }
    private Map<String,Object> one(String sql, Object... args) { List<Map<String,Object>> rows = jdbc.queryForList(sql, args); return rows.isEmpty() ? null : rows.get(0); }
    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> { PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS); for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]); return statement; }, key);
        Number generated = key.getKeys() != null && key.getKeys().get("id") instanceof Number ? (Number)key.getKeys().get("id") : key.getKey();
        if (generated == null) throw new IllegalStateException("The record was not created.");
        return generated.longValue();
    }
    private static Long number(Object raw) { return raw == null ? null : ((Number)raw).longValue(); }
    private static Long optionalId(Map<String,Object> body, String field) { String raw = value(body, field, null); return raw == null ? null : Long.valueOf(raw); }
    private static Long requiredId(Map<String,Object> body, String field) { Long id = optionalId(body, field); if (id == null || id < 1) throw new IllegalArgumentException(field + " is required."); return id; }
    private static String value(Map<String,Object> body, String field, String fallback) { Object raw = body.get(field); return raw == null || raw.toString().trim().isEmpty() ? fallback : raw.toString().trim(); }
    private static String checked(Map<String,Object> body, String field, int max, boolean required) { String text = value(body, field, null); if (required && text == null) throw new IllegalArgumentException(field + " is required."); if (text != null && text.length() > max) throw new IllegalArgumentException(field + " exceeds " + max + " characters."); return text; }
}
