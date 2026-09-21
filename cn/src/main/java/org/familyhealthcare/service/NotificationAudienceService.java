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
        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(jdbc.queryForList("SELECT user_id FROM patient WHERE id=? AND COALESCE(deleted,0)=0", Long.class, patientId));
        ids.addAll(jdbc.queryForList("SELECT user_id FROM care_member WHERE patient_id=?", Long.class, patientId));
        ids.addAll(jdbc.queryForList("SELECT doctor_user_id FROM doctor_patient_assignment WHERE patient_id=? AND status='ACTIVE'", Long.class, patientId));
        ids.addAll(jdbc.queryForList("SELECT grantee_user_id FROM care_access_grant WHERE patient_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>NOW())", Long.class, patientId));
        ids.remove(null);
        return ids;
    }

    public Set<Long> notifyCareTeam(Long patientId, String eventType, String title, String content) {
        Set<Long> ids = recipients(patientId);
        notify(ids, patientId, eventType, title, content);
        return ids;
    }

    public void notify(Collection<Long> userIds, Long patientId, String eventType, String title, String content) {
        for (Long userId : userIds) {
            boolean delivered = delivery.notifyUser(userId, title, content);
            jdbc.update("INSERT INTO notification_delivery_log(recipient_user_id,patient_id,event_type,title,delivery_status,detail) VALUES(?,?,?,?,?,?)",
                    userId, patientId, eventType, title, delivered ? "DELIVERED" : "NO_CHANNEL", delivered ? null : "No enabled channel or delivery failed");
        }
    }

    public List<Long> assignedDoctors(Long patientId) {
        return jdbc.queryForList("SELECT doctor_user_id FROM doctor_patient_assignment WHERE patient_id=? AND status='ACTIVE'", Long.class, patientId);
    }
}
