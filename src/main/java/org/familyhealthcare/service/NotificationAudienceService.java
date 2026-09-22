package org.familyhealthcare.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Routes patient events to the patient, active caregivers, and assigned clinicians. */
@Service
public class NotificationAudienceService {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationDeliveryService delivery;

    public Set<Long> recipients(Long patientId) {
        return recipients(patientId, "EMERGENCY");
    }

    /** Match each outbound event to the recipient's current patient/module authorization. */
    public Set<Long> recipients(Long patientId, String eventType) {
        Set<Long> ids = new LinkedHashSet<>();
        String module = moduleForEvent(eventType);
        for (java.util.Map<String,Object> member : jdbc.queryForList("SELECT user_id,visible_modules FROM care_member WHERE patient_id=?",patientId)) {
            if (includesModule(member.get("visible_modules"),module)) ids.add(((Number)member.get("user_id")).longValue());
        }
        List<java.util.Map<String,Object>> grants = jdbc.queryForList("SELECT grantee_user_id,visible_modules,status,(expires_at IS NULL OR expires_at>NOW()) AS unexpired FROM care_access_grant WHERE patient_id=?",patientId);
        for (java.util.Map<String,Object> grant : grants) ids.remove(((Number)grant.get("grantee_user_id")).longValue());
        for (java.util.Map<String,Object> grant : grants) {
            Object unexpired=grant.get("unexpired");
            if ("ACTIVE".equals(grant.get("status")) && (Boolean.TRUE.equals(unexpired) || unexpired instanceof Number && ((Number)unexpired).intValue()!=0)
                    && includesModule(grant.get("visible_modules"),module)) ids.add(((Number)grant.get("grantee_user_id")).longValue());
        }
        ids.addAll(jdbc.queryForList("SELECT user_id FROM patient WHERE id=? AND COALESCE(deleted,0)=0", Long.class, patientId));
        ids.addAll(assignedDoctors(patientId));
        ids.remove(null);
        ids.retainAll(jdbc.queryForList("SELECT id FROM sys_user WHERE status=1 AND COALESCE(deleted,0)=0",Long.class));
        return ids;
    }

    private boolean includesModule(Object value,String module) {
        String modules=value==null?"":value.toString().trim();
        if(modules.isEmpty())return true;
        if(module==null)return false;
        for(String item:modules.split(","))if(module.equalsIgnoreCase(item.trim()))return true;
        return false;
    }

    private String moduleForEvent(String event) {
        if(event==null)return null;
        if(event.startsWith("APPOINTMENT"))return "APPOINTMENTS";
        if(event.startsWith("MEASUREMENT"))return "MEASUREMENTS";
        if(event.startsWith("PRESCRIPTION")||event.startsWith("MEDICATION"))return "MEDICATION";
        if(event.startsWith("MENTAL"))return "MENTAL";
        if(event.startsWith("VACCINATION"))return "SPECIALTY";
        if(event.startsWith("VISIT"))return "VISITS";
        if(event.startsWith("TREATMENT"))return "TREATMENT";
        if(event.startsWith("REHAB"))return "REHAB";
        if(event.startsWith("INDICATOR"))return "MEDICAL";
        if("EMERGENCY".equals(event))return "EMERGENCY";
        return null;
    }

    public Set<Long> notifyCareTeam(Long patientId, String eventType, String title, String content) {
        Set<Long> ids = recipients(patientId, eventType);
        notify(ids, patientId, eventType, title, content);
        return ids;
    }

    public void notify(Collection<Long> userIds, Long patientId, String eventType, String title, String content) {
        for (Long userId : userIds) {
            boolean delivered = delivery.notifyUser(userId, eventType, title, content);
            jdbc.update("INSERT INTO notification_delivery_log(recipient_user_id,patient_id,event_type,title,delivery_status,detail) VALUES(?,?,?,?,?,?)",
                    userId, patientId, eventType, title, delivered ? "DELIVERED" : "NO_CHANNEL", delivered ? null : "No enabled channel or delivery failed");
        }
    }

    public List<Long> assignedDoctors(Long patientId) {
        return jdbc.queryForList("SELECT doctor_user_id FROM doctor_patient_assignment WHERE patient_id=? AND status='ACTIVE'", Long.class, patientId);
    }
}
