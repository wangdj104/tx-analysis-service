package org.familyhealthcare.service.emergencyassistance;
import org.familyhealthcare.service.workflow.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.*;
/** Immutable human commands. No reopen, medical verdict, caller-supplied snapshot or audience. */
public final class EmergencyAssistanceContracts {
    private EmergencyAssistanceContracts(){
    }
    public static final Set<String> OPERATIONS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("CREATE_REQUEST",
    "ACCEPT","PROGRESS","HANDOFF","RESOLVE","CANCEL_MISTAKE")));
    public static final Set<String> STATES=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("REQUESTED",
    "ACCEPTED","IN_PROGRESS","RESOLVED","CANCELLED_MISTAKE")));
    public static final Set<String> RECEIPT_KEYS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("emergencyEventId",
    "version","eventId","state","cycle")));
    public static final class EmergencyCommand {
        public final String operation,commandKey,locationText,note,reason,outcomeCode;
        public final long patientId,expectedVersion;
        public final Long successorUserId;
        public final Instant actualAt;
        public final BigDecimal latitude,longitude;
        private final Map<String,Object> body;
        private EmergencyCommand(String op,Map<String,Object> raw){
            fields(raw,"commandKey","patientId","expectedVersion","body");
            if(!OPERATIONS.contains(op))throw WorkflowException.invalid("Unsupported emergency action.");
            operation=op;
            commandKey=WorkflowCommandExecutor.validateKey(text(raw.get("commandKey"),"commandKey",36));
            patientId=id(raw.get("patientId"),"patientId");
            expectedVersion=integer(raw.get("expectedVersion"),"expectedVersion",0,9007199254740991L);
            if(!(raw.get("body") instanceof Map))throw WorkflowException.invalid("An emergency command body is required.");
            body=WorkflowData.object(WorkflowData.json(raw.get("body")));
            switch(op){
                case "CREATE_REQUEST":fields(body,"locationText","latitude","longitude");
                break;
                case "ACCEPT":fields(body);
                break;
                case "PROGRESS":fields(body,"actualAt","note");
                break;
                case "HANDOFF":fields(body,"successorUserId","reason");
                break;
                case "RESOLVE":fields(body,"outcomeCode","reason");
                break;
                default:fields(body,"reason");
            }
            locationText=optional(body,"locationText",500);
            note=optional(body,"note",1000);
            reason=optional(body,"reason",1000);
            outcomeCode=body.containsKey("outcomeCode")?choice(body.get("outcomeCode"),"outcomeCode",
            "CONTACTED_HELP",
            "CONTACTED_REQUESTER","HANDED_TO_SERVICES","UNABLE_TO_CONTACT","OTHER_ACTUAL_OUTCOME"):null;
            successorUserId=body.get("successorUserId")==null?null:id(body.get("successorUserId"),"successorUserId");
            actualAt=body.get("actualAt")==null?null:utc(body.get("actualAt"),"actualAt");
            latitude=coordinate(body.get("latitude"),90);
            longitude=coordinate(body.get("longitude"),180);
            if((latitude==null)!=(longitude==null))throw WorkflowException.invalid("Both coordinates are required.");
            if(op.equals("PROGRESS")&&(actualAt==null||note==null))throw WorkflowException.invalid("Actual UTC time and progress note are required.");
            if(Arrays.asList("HANDOFF","RESOLVE","CANCEL_MISTAKE").contains(op)&&reason==null||op.equals("HANDOFF")&&successorUserId==null||op.equals("RESOLVE")&&outcomeCode==null)throw WorkflowException.invalid("An actual outcome, named successor or reason is missing.");
        }
        public String payload(Long target){
            return WorkflowData.json(WorkflowData.map("operation",operation,"emergencyEventId",target,
            "patientId",
            patientId,"body",body));
        }
    }
    private static String optional(Map<String,Object>b,String k,int max){
        return b.get(k)==null?null:text(b.get(k),k,max);
    }
    private static BigDecimal coordinate(Object v,int max){
        if(v==null)return null;
        try{
            if(!(v instanceof String)||!((String)v).matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,7})?"))throw new IllegalArgumentException();
            BigDecimal n=new BigDecimal((String)v);
            if(n.abs().compareTo(BigDecimal.valueOf(max))>0)throw new IllegalArgumentException();
            return n;
        }
        catch(RuntimeException e){
            throw WorkflowException.invalid("Coordinates must be exact decimal strings in range.");
        }
    }
    public static EmergencyCommand parse(String op,Map<String,Object>raw){
        return new EmergencyCommand(op,raw);
    }
    public static void requireMinimalReceipt(Map<String,Object>r){
        try{
            if(r==null||!r.keySet().equals(RECEIPT_KEYS)||!STATES.contains(r.get("state")))throw new IllegalArgumentException();
            for(String k:Arrays.asList("emergencyEventId","eventId","cycle"))id(r.get(k),k);
            integer(r.get("version"),"version",0,9007199254740991L);
        }
        catch(RuntimeException e){
            throw new WorkflowException(409,"RECEIPT_INVALID","Stored emergency receipt is invalid.");
        }
    }
}
