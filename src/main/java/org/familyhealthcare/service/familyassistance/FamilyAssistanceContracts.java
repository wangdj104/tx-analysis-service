package org.familyhealthcare.service.familyassistance;
import org.familyhealthcare.service.workflow.*;
import java.util.*;
import java.time.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.*;
/** 交接只记录明确的人类动作；来源正文和权限不能由客户端传入。 */
public final class FamilyAssistanceContracts {
    private FamilyAssistanceContracts() {
    }
    public static final Set<String> OPERATIONS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("CREATE","ASSIGN","ACCEPT","REJECT","HANDLE","CONFIRM","RETURN","HANDOFF","CANCEL","CLOSE_UNRESOLVED","REOPEN","RECONFIRM_APPOINTMENT")));
    public static final Set<String> STATES=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("REQUESTED","ACCEPTED","AWAITING_CONFIRMATION","COMPLETED","CANCELLED","CLOSED_UNRESOLVED")));
    public static final Set<String> RECEIPT_KEYS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("assistanceId","version","eventId","state","cycle")));
    public static final class AssistanceCommand {
        public final String operation,commandKey,sourceKind,reason,fingerprint;
        public final long patientId,expectedVersion;
        public final Long careItemId,careAppointmentId,assignedUserId;
        public final Instant deadlineAt;
        private final Map<String,Object> body;
        private AssistanceCommand(String op,Map<String,Object> raw) {
            fields(raw,"commandKey","patientId","expectedVersion","body");
            if(!OPERATIONS.contains(op))throw WorkflowException.invalid("Unsupported assistance action.");
            operation=op;
            commandKey=WorkflowCommandExecutor.validateKey(text(raw.get("commandKey"),"commandKey",36));
            patientId=id(raw.get("patientId"),"patientId");
            expectedVersion=integer(raw.get("expectedVersion"),"expectedVersion",0,9007199254740991L);
            if(!(raw.get("body") instanceof Map))throw WorkflowException.invalid("An assistance body is required.");
            body=new LinkedHashMap<>(WorkflowData.object(WorkflowData.json(raw.get("body"))));
            switch(op) {
                case "CREATE":fields(body,"sourceKind","careItemId","careAppointmentId","assignedUserId","deadlineAt");
                break;
                case "ASSIGN":fields(body,"assignedUserId");
                break;
                case "HANDOFF":fields(body,"assignedUserId","reason");
                break;
                case "RECONFIRM_APPOINTMENT":fields(body,"fingerprint");
                break;
                case "ACCEPT":case "CONFIRM":fields(body);
                break;
                default:fields(body,"reason");
            }
            sourceKind=body.containsKey("sourceKind")?choice(body.get("sourceKind"),"sourceKind","CARE_ITEM","CARE_APPOINTMENT"):null;
            careItemId=optional(body,"careItemId");
            careAppointmentId=optional(body,"careAppointmentId");
            assignedUserId=optional(body,"assignedUserId");
            reason=body.containsKey("reason")?text(body.get("reason"),"reason",1000):null;
            fingerprint=body.containsKey("fingerprint")?text(body.get("fingerprint"),"fingerprint",64):null;
            deadlineAt=body.get("deadlineAt")==null?null:utc(body.get("deadlineAt"),"deadlineAt");
            if(op.equals("CREATE")&&(sourceKind==null||!(sourceKind.equals("CARE_ITEM")?careItemId!=null&&careAppointmentId==null:careAppointmentId!=null&&careItemId==null)))throw WorkflowException.invalid("Select exactly one typed native source.");
            if(Arrays.asList("ASSIGN","HANDOFF").contains(op)&&assignedUserId==null)throw WorkflowException.invalid("Select a named handler.");
            if(Arrays.asList("REJECT","HANDLE","RETURN","HANDOFF","CANCEL","CLOSE_UNRESOLVED","REOPEN").contains(op)&&reason==null)throw WorkflowException.invalid("A real reason is required.");
            if(op.equals("RECONFIRM_APPOINTMENT")&&(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")))throw WorkflowException.invalid("Select the current appointment fingerprint.");
        }
        public String payload(Long target) {
            return WorkflowData.json(WorkflowData.map("operation",operation,"assistanceId",target,"patientId",patientId,"body",body));
        }
    }
    private static Long optional(Map<String,Object> m,String k) {
        return m.get(k)==null?null:id(m.get(k),k);
    }
    public static AssistanceCommand parse(String op,Map<String,Object> raw) {
        return new AssistanceCommand(op,raw);
    }
    public static void requireMinimalReceipt(Map<String,Object> r) {
        try {
            if(r==null||!r.keySet().equals(RECEIPT_KEYS)||!STATES.contains(r.get("state")))throw new IllegalArgumentException();
            for(String k:Arrays.asList("assistanceId","eventId","cycle"))id(r.get(k),k);
            integer(r.get("version"),"version",0,9007199254740991L);
        }
        catch(RuntimeException e) {
            throw new WorkflowException(409,"RECEIPT_INVALID","Stored assistance receipt is invalid.");
        }
    }
}
