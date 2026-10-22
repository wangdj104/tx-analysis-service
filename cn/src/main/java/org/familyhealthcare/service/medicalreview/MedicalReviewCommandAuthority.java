package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionAuthorization;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Objects;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContentFingerprint.integrity;

/** Uses the existing locked current predicate; classification never grants permission. */
final class MedicalReviewCommandAuthority {
    private final DataSource dataSource;private final MedicalRecordReviewAccess access;
    MedicalReviewCommandAuthority(JdbcTemplate jdbc,MedicalRecordReviewAccess access){
        if(jdbc==null||jdbc.getDataSource()==null||access==null)throw MedicalReviewSchema.notReady();
        this.dataSource=jdbc.getDataSource();this.access=access;
    }
    void requireDataSource(DataSource expected){
        if(expected==null||dataSource!=expected)throw MedicalReviewSchema.notReady();
        try{access.requireDataSource(expected);}catch(IllegalStateException failure){throw MedicalReviewSchema.notReady();}
    }
    String require(Connection c,MedicalRecord source,long actor,Channel channel,Operation operation)throws SQLException{
        requireActor(actor);requireDataSource(dataSource);
        if(channel==Channel.DOCTOR&&source.getPatientId()==null)throw MedicalRecordCorrectionAuthorization.denied();
        boolean workspace=channel==Channel.DOCTOR&&(operation==Operation.APPROVE||operation==Operation.REJECT);
        try{access.requireLockedCurrent(source,actor,true,workspace);}
        catch(IllegalStateException denied){if("Access denied to source record.".equals(denied.getMessage()))throw MedicalRecordCorrectionAuthorization.denied();throw denied;}
        requireActor(actor);
        if(role(c,actor,"admin"))return "ADMIN";
        if(source.getPatientId()!=null&&role(c,actor,"doctor")&&exists(c,"SELECT EXISTS(SELECT 1 FROM doctor_patient_assignment WHERE doctor_user_id=? AND patient_id=? AND status='ACTIVE')",actor,source.getPatientId()))return "ASSIGNED_DOCTOR";
        if(workspace)throw MedicalRecordCorrectionAuthorization.denied();
        Long owner=source.getUserId();
        if(source.getPatientId()!=null){
            try(PreparedStatement s=c.prepareStatement("SELECT id,user_id FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0 LIMIT 2")){
                s.setQueryTimeout(5);s.setObject(1,source.getPatientId());
                try(ResultSet r=s.executeQuery()){
                    if(!r.next())throw MedicalRecordCorrectionAuthorization.denied();
                    long patient=r.getLong("id");if(r.wasNull()||patient!=source.getPatientId())throw integrity();
                    long value=r.getLong("user_id");owner=r.wasNull()?null:value;MedicalReviewContentFingerprint.nullableId(owner);
                    if(r.next())throw integrity();
                }
            }
        }
        requireActor(actor);return Objects.equals(Long.valueOf(actor),owner)?"OWNER":"DELEGATE";
    }
    private boolean role(Connection c,long actor,String role)throws SQLException{return exists(c,"SELECT EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0)",actor,role);}
    private boolean exists(Connection c,String sql,Object...args)throws SQLException{
        try(PreparedStatement s=c.prepareStatement(sql)){s.setQueryTimeout(5);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);try(ResultSet r=s.executeQuery()){if(!r.next())throw integrity();int value=r.getInt(1);if(r.wasNull()||(value!=0&&value!=1)||r.next())throw integrity();return value==1;}}
    }
    static void requireActor(long actor){if(!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId()))throw MedicalRecordCorrectionAuthorization.denied();}
}
