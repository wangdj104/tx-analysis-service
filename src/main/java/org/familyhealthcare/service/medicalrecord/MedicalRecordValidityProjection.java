package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.util.*;

/** 仅投影来源状态，不授予任何读取或写入权限。结构结果只在本次请求或事务内复用。 */
@Component
public class MedicalRecordValidityProjection {
    private final JdbcTemplate jdbc;
    private final MedicalRecordCorrectionSchema schema;
    private final boolean enabled;
    private final Object transactionKey=new Object();
    private final String requestKey=getClass().getName()+".storage";
    public MedicalRecordValidityProjection(JdbcTemplate jdbc,MedicalRecordCorrectionSchema schema,
            @Value("${medical-record-correction.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.schema=schema;this.enabled=enabled;
    }
    public Scope open() {
        MedicalRecordCorrectionSchema.Status status=null;
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            status=(MedicalRecordCorrectionSchema.Status)TransactionSynchronizationManager.getResource(transactionKey);
            if(status==null) {
                status=inspect();TransactionSynchronizationManager.bindResource(transactionKey,status);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void suspend(){TransactionSynchronizationManager.unbindResourceIfPossible(transactionKey);}
                    @Override public void resume(){TransactionSynchronizationManager.bindResource(transactionKey,stored);}
                    private final MedicalRecordCorrectionSchema.Status stored=(MedicalRecordCorrectionSchema.Status)TransactionSynchronizationManager.getResource(transactionKey);
                    @Override public void afterCompletion(int completion){TransactionSynchronizationManager.unbindResourceIfPossible(transactionKey);}
                });
            }
        } else {
            RequestAttributes request=RequestContextHolder.getRequestAttributes();
            if(request!=null)status=(MedicalRecordCorrectionSchema.Status)request.getAttribute(requestKey,RequestAttributes.SCOPE_REQUEST);
            if(status==null){status=inspect();if(request!=null)request.setAttribute(requestKey,status,RequestAttributes.SCOPE_REQUEST);}
        }
        return new Scope(status);
    }
    private MedicalRecordCorrectionSchema.Status inspect() {
        MedicalRecordCorrectionSchema.Status status=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<MedicalRecordCorrectionSchema.Status>)schema::inspectStorage);
        if(status==MedicalRecordCorrectionSchema.Status.INCOMPATIBLE||enabled&&status!=MedicalRecordCorrectionSchema.Status.READY)throw MedicalRecordCorrectionSchema.notReady();
        return status;
    }
    public final class Scope {
        public final MedicalRecordCorrectionSchema.Status storage;
        private Scope(MedicalRecordCorrectionSchema.Status status){storage=status;}
        public boolean ready(){return storage==MedicalRecordCorrectionSchema.Status.READY;}
        /** 所有调用者只能传服务器常量别名；这是查询条件，不是权限条件。 */
        public String activeSql(String alias) {
            alias(alias);return ready()?"NOT EXISTS (SELECT 1 FROM medical_record_correction_state mv WHERE mv.record_id="+alias+"id AND mv.validity='INVALIDATED')":"1=1";
        }
        public String eligibleSql(String alias) {
            alias(alias);return ready()?"(NOT EXISTS (SELECT 1 FROM medical_record_correction_state mv WHERE mv.record_id="+alias+"id) OR ("+alias+"verification_status='VERIFIED' AND EXISTS (SELECT 1 FROM medical_record_correction_state mv WHERE mv.record_id="+alias+"id AND mv.validity='ACTIVE' AND mv.current_content_version="+alias+"content_version)))":"1=1";
        }
        public String untrackedSql(String alias){alias(alias);return ready()?"NOT EXISTS (SELECT 1 FROM medical_record_correction_state mv WHERE mv.record_id="+alias+"id)":"1=1";}
        private void alias(String alias){if(!Arrays.asList("","r.","m.","n.","medical_record.").contains(alias))throw new IllegalArgumentException("Invalid source alias.");}
        public Map<Long,State> batch(Collection<Long> input) {
            LinkedHashSet<Long> ids=new LinkedHashSet<>(input);
            if(input.size()>100||ids.contains(null))throw MedicalRecordCorrectionContracts.limit();
            if(ids.isEmpty())return Collections.emptyMap();
            for(long id:ids)MedicalRecordCorrectionContracts.id(id);
            String columns="r.id,r.patient_id,r.user_id,r.content_version,r.verification_status";
            String joins="";
            if(ready()) {
                columns+=",s.record_id AS managed_id,s.patient_id AS state_patient,s.owner_user_id,s.current_content_version,s.validity,h.after_version,h.validity AS head_validity,CASE WHEN EXISTS(SELECT 1 FROM medical_record_correction_history p WHERE p.record_id=r.id AND p.history_sequence>s.current_history_sequence) THEN 1 ELSE 0 END AS pending";
                joins=" LEFT JOIN medical_record_correction_state s ON s.record_id=r.id LEFT JOIN medical_record_correction_history h ON h.record_id=s.record_id AND h.history_sequence=s.current_history_sequence";
            }
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            List<State> rows=jdbc.query("SELECT "+columns+" FROM medical_record r"+joins+" WHERE r.id IN ("+placeholders+") ORDER BY r.id",(r,n)->{
                long id=r.getLong("id");Long patient=WorkflowData.nullableId(r,"patient_id"),owner=WorkflowData.nullableId(r,"user_id"),version=WorkflowData.nullableId(r,"content_version");
                boolean managed=ready()&&r.getObject("managed_id")!=null;
                String validity=managed?r.getString("validity"):"UNTRACKED";
                if(managed&&(!Objects.equals(patient,WorkflowData.nullableId(r,"state_patient"))||!Objects.equals(owner,WorkflowData.nullableId(r,"owner_user_id"))||!Objects.equals(version,WorkflowData.nullableId(r,"current_content_version"))||!Objects.equals(version,WorkflowData.nullableId(r,"after_version"))||!Objects.equals(validity,r.getString("head_validity"))||r.getInt("pending")!=0||!("ACTIVE".equals(validity)||"INVALIDATED".equals(validity))))throw MedicalRecordCorrectionContracts.integrity();
                if(managed&&(version==null||version<1||version>MedicalRecordCorrectionContracts.MAX_ID))throw MedicalRecordCorrectionContracts.integrity();
                return new State(id,patient,owner,version,managed,validity,r.getString("verification_status"));
            },ids.toArray());
            Map<Long,State> result=new LinkedHashMap<>();for(State row:rows)result.put(row.id,row);return result;
        }
        /** 新组合只读消费者使用同一批数据的不可变签名；不缓存来源内容，也不授访问权。 */
        public Map<Long,State> batchSnapshot(Collection<Long> input) {
            LinkedHashSet<Long> ids=new LinkedHashSet<>(input);
            if(input.size()>100||ids.contains(null))throw MedicalRecordCorrectionContracts.limit();
            if(ids.isEmpty())return Collections.emptyMap();
            for(long id:ids)MedicalRecordCorrectionContracts.id(id);
            String columns="r.id,r.patient_id,r.user_id,r.content_version,r.verification_status";
            String joins="";
            if(ready()) {
                columns+=",s.record_id AS managed_id,s.patient_id AS state_patient,s.owner_user_id,s.current_content_version,s.validity,h.after_version,h.validity AS head_validity,s.current_history_sequence,s.content_digest AS state_digest,h.id AS head_id,h.history_sequence AS head_sequence,h.content_digest AS head_digest,CASE WHEN EXISTS(SELECT 1 FROM medical_record_correction_history p WHERE p.record_id=r.id AND p.history_sequence>s.current_history_sequence) THEN 1 ELSE 0 END AS pending";
                joins=" LEFT JOIN medical_record_correction_state s ON s.record_id=r.id LEFT JOIN medical_record_correction_history h ON h.record_id=s.record_id AND h.history_sequence=s.current_history_sequence";
            }
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            List<State> rows=jdbc.query("SELECT "+columns+" FROM medical_record r"+joins+" WHERE r.id IN ("+placeholders+") ORDER BY r.id",statement->{statement.setQueryTimeout(5);int index=1;for(long source:ids)statement.setLong(index++,source);},(r,n)->{
                long id=r.getLong("id");Long patient=WorkflowData.nullableId(r,"patient_id"),owner=WorkflowData.nullableId(r,"user_id"),version=WorkflowData.nullableId(r,"content_version");
                boolean managed=ready()&&r.getObject("managed_id")!=null;
                String validity=managed?r.getString("validity"):"UNTRACKED";
                if(managed&&(!Objects.equals(patient,WorkflowData.nullableId(r,"state_patient"))||!Objects.equals(owner,WorkflowData.nullableId(r,"owner_user_id"))||!Objects.equals(version,WorkflowData.nullableId(r,"current_content_version"))||!Objects.equals(version,WorkflowData.nullableId(r,"after_version"))||!Objects.equals(validity,r.getString("head_validity"))||r.getInt("pending")!=0||!("ACTIVE".equals(validity)||"INVALIDATED".equals(validity))))throw MedicalRecordCorrectionContracts.integrity();
                if(managed&&(version==null||version<1||version>MedicalRecordCorrectionContracts.MAX_ID))throw MedicalRecordCorrectionContracts.integrity();
                String signature=WorkflowData.json(WorkflowData.map("id",id,"patient",patient,"owner",owner,"version",version,"review",r.getString("verification_status"),
                        "managed",ready()?r.getObject("managed_id"):null,"statePatient",ready()?r.getObject("state_patient"):null,"stateOwner",ready()?r.getObject("owner_user_id"):null,
                        "stateVersion",ready()?r.getObject("current_content_version"):null,"validity",ready()?r.getString("validity"):null,
                        "sequence",ready()?r.getObject("current_history_sequence"):null,"stateDigest",ready()?r.getString("state_digest"):null,
                        "head",ready()?r.getObject("head_id"):null,"headSequence",ready()?r.getObject("head_sequence"):null,"headVersion",ready()?r.getObject("after_version"):null,
                        "headValidity",ready()?r.getString("head_validity"):null,"headDigest",ready()?r.getString("head_digest"):null,"pending",ready()?r.getObject("pending"):null));
                return new State(id,patient,owner,version,managed,validity,r.getString("verification_status"),signature);
            });
            Map<Long,State> result=new LinkedHashMap<>();for(State row:rows)result.put(row.id,row);return result;
        }
        public State one(long id){return batch(Collections.singleton(id)).get(id);}
        public void projectRows(List<Map<String,Object>> rows,String idKey) {
            for(int offset=0;offset<rows.size();offset+=100){List<Map<String,Object>> part=rows.subList(offset,Math.min(offset+100,rows.size()));List<Long> ids=new ArrayList<>();for(Map<String,Object> row:part)ids.add(((Number)row.get(idKey)).longValue());Map<Long,State> states=batch(ids);
                for(Map<String,Object> row:part){State state=states.get(((Number)row.get(idKey)).longValue());if(state==null){row.put("sourceStatus","SOURCE_UNAVAILABLE");continue;}boolean captured=row.containsKey("contentVersion");boolean changed=captured&&(!Objects.equals(row.get("contentVersion"),state.version)||!Objects.equals(row.get("verificationStatus"),state.review));row.put("managed",state.managed);row.put("validity",state.validity);if(!captured){row.put("verificationStatus",state.review);row.put("contentVersion",state.version);}row.put("sourceStatus",state.managed&&changed?"SOURCE_CHANGED":state.status());}
            }
        }
        public void project(List<MedicalRecord> records) {
            for(int offset=0;offset<records.size();offset+=100){List<MedicalRecord> part=records.subList(offset,Math.min(offset+100,records.size()));List<Long> ids=new ArrayList<>();for(MedicalRecord record:part)ids.add(record.getId());Map<Long,State> states=batch(ids);
                for(MedicalRecord record:part){State state=states.get(record.getId());if(state==null)throw MedicalRecordCorrectionContracts.integrity();record.setManaged(state.managed);record.setValidity(state.validity);record.setSourceStatus(state.managed&&(!Objects.equals(record.getContentVersion(),state.version)||!Objects.equals(record.getVerificationStatus(),state.review))?"SOURCE_CHANGED":state.status());}
            }
        }
    }
    public static final class State {
        public final long id;public final Long patientId,ownerId,version;public final boolean managed;public final String validity,review,signature;
        private State(long id,Long patient,Long owner,Long version,boolean managed,String validity,String review){this(id,patient,owner,version,managed,validity,review,null);}
        private State(long id,Long patient,Long owner,Long version,boolean managed,String validity,String review,String signature){this.id=id;this.patientId=patient;this.ownerId=owner;this.version=version;this.managed=managed;this.validity=validity;this.review=review;this.signature=signature;}
        public boolean active(){return !managed||"ACTIVE".equals(validity);}
        public boolean eligible(){return !managed||active()&&"VERIFIED".equals(review);}
        public String status(){return !managed?"UNTRACKED":!active()?"SOURCE_INVALIDATED":!eligible()?"SOURCE_REVIEW_REQUIRED":"CURRENT";}
    }
    public static BigDecimal exactNumeric(String value) {
        if(value==null||value.length()>500||!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))return null;
        try{BigDecimal number=new BigDecimal(value);return number;}catch(NumberFormatException invalid){return null;}
    }
    public static BigDecimal plotNumber(String value){BigDecimal number=exactNumeric(value);if(number==null)return null;double plotted=number.doubleValue();return Double.isFinite(plotted)&&(plotted!=0.0||number.signum()==0)?number:null;}
    public static boolean exactUnit(String actual,String expected){return actual!=null&&!actual.trim().isEmpty()&&expected!=null&&!expected.trim().isEmpty()&&actual.equals(expected);}
    public static Integer sourceFlag(Integer value){return value!=null&&(value==-1||value==0||value==1)?value:null;}
}
