package org.familyhealthcare.service.emergencyassistance;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.*;
/** Small legacy-card projection seam. Callers own current EMERGENCY authorization. No journey-service dependency. */
@Service public class EmergencyInformationSnapshotService {
    private final JdbcTemplate jdbc;
    public EmergencyInformationSnapshotService(JdbcTemplate jdbc){
        this.jdbc=jdbc;
    }
    public Map<String,Object> project(long patient){
        Map<String,Object> card=new LinkedHashMap<>();
        card.put("patient",one("SELECT id,name,gender,birth_date,emergency_contact,emergency_phone,medical_history FROM patient WHERE id=?",
        patient));
        card.put("clinical",one("SELECT allergy_drugs,blood_type,primary_diagnosis,dialysis_type,vascular_access,target_dry_weight,remark FROM patient_clinical WHERE patient_id=?",
        patient));
        card.put("medications",jdbc.queryForList("SELECT drug_name,default_dosage,remark FROM medication WHERE patient_id=? AND is_active=1 ORDER BY drug_name",
        patient));
        card.put("generatedAt",LocalDateTime.now().toString());
        card.put("offlineReadable",true);
        card.put("disclaimer","Emergency information is patient-maintained and must be clinically verified.");
        return card;
    }
    private Map<String,Object> one(String sql,long p){
        List<Map<String,Object>>r=jdbc.queryForList(sql,p);
        return r.isEmpty()?null:r.get(0);
    }
}
