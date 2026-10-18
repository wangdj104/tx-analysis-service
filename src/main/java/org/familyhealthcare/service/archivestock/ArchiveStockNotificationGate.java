package org.familyhealthcare.service.archivestock;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.familyhealthcare.service.archivestock.ArchiveStockNotificationContracts.*;

/** A sidecar admission is always terminal in the caller's existing lock graph. No transport, no swallowed SQL failure. */
public final class ArchiveStockNotificationGate {
    private final JdbcTemplate jdbc;
    private final ArchiveStockNotificationSchema schema=new ArchiveStockNotificationSchema();
    private final ArchiveStockNotificationRepository policies;
    public ArchiveStockNotificationGate(JdbcTemplate jdbc){this.jdbc=jdbc;this.policies=new ArchiveStockNotificationRepository(jdbc);}
    /** Immutable original admission, carried separately from native notification maps. */
    public static final class Token {
        private final long patientId,stockId;
        private final Long controlVersion;
        private final long watermark;
        private Token(long patient,long stock,ArchiveStockNotificationRepository.State state){patientId=patient;stockId=stock;controlVersion=state==null?null:state.controlVersion;watermark=state==null?0:state.enabledAfterStockVersion;}
        public boolean marked(){return controlVersion!=null;}
        private boolean same(long patient,long stock,ArchiveStockNotificationRepository.State state){
            return patientId==patient&&stockId==stock&&Objects.equals(controlVersion,state==null?null:state.controlVersion)&&watermark==(state==null?0:state.enabledAfterStockVersion);
        }
    }
    /** Completed non-READY metadata denies without touching missing tables; execution/row-read failures propagate. */
    public Token admit(long patient,long stock,Long aggregateVersion,Token original,boolean lock){
        id(patient);id(stock);if(lock)WorkflowData.mutationTransaction(jdbc);
        if(schema.inspect(jdbc)!=ArchiveStockNotificationSchema.Status.READY)return null;
        ArchiveStockNotificationRepository.State state=policies.state(stock,lock);
        if(state!=null&&(!state.enabled||aggregateVersion!=null&&version(aggregateVersion)<=state.enabledAfterStockVersion))return null;
        if(original!=null&&!original.same(patient,stock,state))return null;
        if(state!=null){
            // Already locked by mutation callers; read-only status projections use this only as a hint.
            List<Map<String,Object>> source=jdbc.queryForList("SELECT m.is_active,s.quantity,s.unit,s.warning_quantity,s.warning_days FROM medication_stock s JOIN medication m ON m.id=s.medication_id AND m.patient_id=s.patient_id WHERE s.id=? AND s.patient_id=?",stock,patient);
            if(source.size()!=1)return null;Map<String,Object> row=source.get(0);
            if(knownActive(row.get("is_active"))==null||row.get("quantity")==null||row.get("warning_quantity")==null||row.get("warning_days")==null||!(row.get("unit") instanceof String)||((String)row.get("unit")).trim().isEmpty())return null;
        }
        return original==null?new Token(patient,stock,state):original;
    }
    public ArchiveStockNotificationSchema.Status status(){return schema.inspect(jdbc);}
    public boolean ready(){return status()==ArchiveStockNotificationSchema.Status.READY;}
    public static Boolean knownActive(Object value){
        if(value==null)return null;if(value instanceof Boolean)return (Boolean)value;if(!(value instanceof Number))return null;
        BigDecimal number=new BigDecimal(value.toString());if(number.compareTo(BigDecimal.ZERO)==0)return false;if(number.compareTo(BigDecimal.ONE)==0)return true;return null;
    }
}
