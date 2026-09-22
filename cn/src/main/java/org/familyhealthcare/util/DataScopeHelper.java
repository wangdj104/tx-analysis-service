package org.familyhealthcare.util;

import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.PatientMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/** Patient scope follows both the authorized module and whether an operation reads or writes. */
@Component
public class DataScopeHelper {
    @Autowired private PatientMapper patientMapper;
    @Autowired private org.familyhealthcare.service.CareMembershipService careMembership;
    @Autowired private JdbcTemplate jdbcTemplate;

    public Long requireUserId() {
        Long id=CurrentUserUtil.getCurrentUserId();
        if(id==null)throw new IllegalStateException("You are not signed in. Please sign in first.");
        return id;
    }
    public void assertOwnedByCurrentUser(Long owner) {
        if(!CurrentUserUtil.isAdmin()&&!Objects.equals(owner,requireUserId()))throw new IllegalStateException("Access denied to this data.");
    }
    public void requireAdmin() {
        if(!CurrentUserUtil.isAdmin())throw new IllegalStateException("Only administrators can perform this action.");
    }
    public <T> void applyUserScope(QueryWrapper<T> query) {
        if(CurrentUserUtil.isAdmin())return;
        Long uid=requireUserId();List<Long> ids=accessiblePatientIds(uid);
        query.and(q->{q.and(legacy->legacy.eq("user_id",uid).isNull("patient_id"));if(!ids.isEmpty())q.or().in("patient_id",ids);});
    }
    public Patient requirePatient(Long patientId) {
        return requirePatientAccess(patientId,requestModule(),requestWrites());
    }
    /** Explicit intent is used by multi-module services and avoids inferring access from unrelated routes. */
    public Patient requirePatientAccess(Long patientId,String module,boolean write) {
        if(patientId==null)return null;
        Patient patient=patientMapper.selectById(patientId);
        if(patient==null||Objects.equals(patient.getDeleted(),1))throw new IllegalStateException("The patient does not exist.");
        if(!CurrentUserUtil.isAdmin()&&!Objects.equals(patient.getUserId(),requireUserId())
                &&!accessiblePatientIds(requireUserId(),module,write).contains(patientId))
            throw new IllegalStateException("You do not have access to this patient or action.");
        return patient;
    }
    public List<Long> accessiblePatientIds(Long userId) {
        return accessiblePatientIds(userId,requestModule(),requestWrites());
    }
    private List<Long> accessiblePatientIds(Long uid,String module,boolean write) {
        // Existing family-member access levels are preserved, including WRITE on legacy bindings.
        LinkedHashSet<Long> ids=new LinkedHashSet<>();
        for(Map<String,Object> member:jdbcTemplate.queryForList("SELECT patient_id,access_level,visible_modules FROM care_member WHERE user_id=?",uid)) {
            if(write&&!Arrays.asList("WRITE","PROXY").contains(String.valueOf(member.get("access_level"))))continue;
            if(matchesModule(member.get("visible_modules"),module))ids.add(((Number)member.get("patient_id")).longValue());
        }
        List<Map<String,Object>> grants=jdbcTemplate.queryForList(
                "SELECT patient_id,access_level,visible_modules,status,(expires_at IS NULL OR expires_at>NOW()) AS unexpired FROM care_access_grant WHERE grantee_user_id=?",uid);
        // An explicit restriction or revocation must not fall back to unrestricted family membership.
        for(Map<String,Object> grant:grants)ids.remove(((Number)grant.get("patient_id")).longValue());
        for(Map<String,Object> grant:grants) {
            if(!"ACTIVE".equals(grant.get("status"))||!truth(grant.get("unexpired")))continue;
            if(write&&!Arrays.asList("WRITE","PROXY").contains(String.valueOf(grant.get("access_level"))))continue;
            if(matchesModule(grant.get("visible_modules"),module))ids.add(((Number)grant.get("patient_id")).longValue());
        }
        ids.addAll(jdbcTemplate.queryForList("SELECT id FROM patient WHERE user_id=? AND COALESCE(deleted,0)=0",Long.class,uid));
        if(isDoctor(uid))ids.addAll(jdbcTemplate.queryForList(
                "SELECT patient_id FROM doctor_patient_assignment WHERE doctor_user_id=? AND status='ACTIVE'",Long.class,uid));
        return new ArrayList<>(ids);
    }
    private boolean matchesModule(Object configured,String module) {
        String value=configured==null?"":configured.toString().trim();
        if(value.isEmpty()||"*".equals(module))return true;
        if(module==null)return false; // Aggregated/full-record views need a full-record grant.
        for(String allowed:value.split(","))if(module.equalsIgnoreCase(allowed.trim()))return true;
        return false;
    }
    private boolean truth(Object value){return Boolean.TRUE.equals(value)||value instanceof Number&&((Number)value).intValue()!=0;}
    private boolean isDoctor(Long uid) {
        if(request()!=null)return CurrentUserUtil.hasRole("doctor");
        // Scheduled jobs have no HTTP role attributes: resolve the current, enabled role from the database.
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 AND r.role_code='doctor' AND r.status=1 AND COALESCE(r.deleted,0)=0",Integer.class,uid)>0;
    }
    private HttpServletRequest request() {
        Object attributes=RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes?((ServletRequestAttributes)attributes).getRequest():null;
    }
    private String path() {
        HttpServletRequest request=request();if(request==null)return "";
        String path=request.getRequestURI();return path.startsWith("/api/")?path.substring(4):path;
    }
    private boolean requestWrites() {
        HttpServletRequest request=request();
        if(request==null||request.getMethod()==null||request.getMethod().isEmpty())return false;
        if(Arrays.asList("GET","HEAD","OPTIONS").contains(request.getMethod().toUpperCase(Locale.ROOT)))return false;
        // These POST endpoints only preview/stream data; importing and recording still require write access.
        return !Arrays.asList("/dialysis/text-import/preview","/clinical-import/preview","/data-export/csv","/health-report/generate").contains(path());
    }
    private String requestModule() {
        String path=path();
        if(path.equals("/patient/names"))return "*"; // Names only, never an entire clinical profile.
        if(path.startsWith("/medication")||path.startsWith("/care/stock")||path.startsWith("/family-health/intakes"))return "MEDICATION";
        if(path.startsWith("/medical-record")||path.startsWith("/health-indicator"))return "MEDICAL";
        if(path.startsWith("/bp-self-monitor")||path.startsWith("/care/quick-vitals")||path.startsWith("/family-health/target"))return "MEASUREMENTS";
        if(path.startsWith("/dialysis")||path.startsWith("/dry-weight")||path.startsWith("/bp-pattern")||path.startsWith("/family-health/dialysis-schedules"))return "DIALYSIS";
        if(path.startsWith("/care-journey/measurements"))return "MEASUREMENTS";
        if(path.startsWith("/care-journey/appointments")||path.startsWith("/care-journey/doctor-schedules"))return "APPOINTMENTS";
        if(path.startsWith("/care-journey/visits"))return "VISITS";
        if(path.startsWith("/care-journey/prescriptions"))return "MEDICATION";
        if(path.startsWith("/care-journey/consultation"))return "CONSULTATION";
        if(path.startsWith("/care-journey/treatment-plans"))return "TREATMENT";
        if(path.startsWith("/care-journey/rehab-checkins"))return "REHAB";
        if(path.startsWith("/care-journey/emergency")||path.equals("/care-journey/emergencies"))return "EMERGENCY";
        if(path.startsWith("/care-journey/specialty"))return "SPECIALTY";
        if(path.startsWith("/care-journey/mental-"))return "MENTAL";
        return null;
    }
    public void requirePatientOrOwner(Long patientId,Long ownerId) {
        if(patientId!=null)requirePatient(patientId);else assertOwnedByCurrentUser(ownerId);
    }
    public String resolvePatientName(Long patientId) {
        Patient patient=requirePatient(patientId);return patient==null?null:patient.getName();
    }
}
