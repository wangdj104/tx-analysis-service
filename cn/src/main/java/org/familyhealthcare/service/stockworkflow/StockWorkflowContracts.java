package org.familyhealthcare.service.stockworkflow;
import org.familyhealthcare.service.workflow.*;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.*;
/** Explicit human amounts only. No dosage parser, conversion, or client-supplied authority. */
public final class StockWorkflowContracts  {
    private StockWorkflowContracts()  {
    }
    public static final Set<String> OPERATIONS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("ADOPT_STOCK","RECORD_REPLENISHMENT","RECORD_COUNT","RESOLVE_INTAKE","RECONCILE","CHANGE_RULE","CANCEL_MISTAKE")));
    public static final Set<String> RECEIPT_KEYS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("stockId","version","eventId","state","cycle","workId","countId","resolutionId")));
    public static final class StockCommand  {
        public final String operation,commandKey,reason,unit,countChoice;
        public final long patientId,expectedVersion;
        public final Long assignedUserId,intakeId,actionId,countId,countVersion;
        public final BigDecimal quantity,warningQuantity,dailyUseQuantity;
        public final Integer warningDays;
        public final Instant actualAt,countedAt;
        private final Map<String,Object> body;
        private StockCommand(String op,Map<String,Object> raw)  {
            fields(raw,"commandKey","patientId","expectedVersion","body");
            if(!OPERATIONS.contains(op))throw WorkflowException.invalid("Unsupported stock action.");
            operation=op;
            commandKey=WorkflowCommandExecutor.validateKey(text(raw.get("commandKey"),"commandKey",36));
            patientId=id(raw.get("patientId"),"patientId");
            expectedVersion=integer(raw.get("expectedVersion"),"expectedVersion",0,9007199254740991L);
            if(!(raw.get("body") instanceof Map))throw WorkflowException.invalid("A stock command body is required.");
            body=new LinkedHashMap<>(WorkflowData.object(WorkflowData.json(raw.get("body"))));
            switch(op)  {
                case "ADOPT_STOCK":fields(body,"assignedUserId","dailyUseQuantity","unit","reason");
                break;
                case "RECORD_REPLENISHMENT":fields(body,"quantity","unit","actualAt","reason");
                break;
                case "RECORD_COUNT":fields(body,"quantity","unit","countedAt","reason");
                break;
                case "RESOLVE_INTAKE":fields(body,"intakeId","actionId","countChoice","countId","countVersion","reason");
                break;
                case "CHANGE_RULE":fields(body,"warningQuantity","warningDays","dailyUseQuantity","assignedUserId","unit","reason");
                break;
                default:fields(body,"reason");
            }
            reason=text(body.get("reason"),"reason",1000);
            unit=body.containsKey("unit")?text(body.get("unit"),"unit",20):null;
            quantity=body.containsKey("quantity")?decimal(body.get("quantity"),"RECORD_COUNT".equals(op)):null;
            if(Arrays.asList("RECORD_COUNT","RECORD_REPLENISHMENT").contains(op)&&quantity==null)throw WorkflowException.invalid("An explicit quantity is required.");
            warningQuantity="CHANGE_RULE".equals(op)?decimal(body.get("warningQuantity"),true):null;
            warningDays="CHANGE_RULE".equals(op)?(int)integer(body.get("warningDays"),"warningDays",0,36500):null;
            dailyUseQuantity=body.get("dailyUseQuantity")==null?null:decimal(body.get("dailyUseQuantity"),false);
            assignedUserId=optionalId(body,"assignedUserId");
            intakeId=optionalId(body,"intakeId");
            actionId=optionalId(body,"actionId");
            countId=optionalId(body,"countId");
            countVersion=body.containsKey("countVersion")?integer(body.get("countVersion"),"countVersion",0,9007199254740991L):null;
            countChoice=body.containsKey("countChoice")?choice(body.get("countChoice"),"countChoice","ACTUAL_AMOUNT","INCLUDED_IN_COUNT"):null;
            actualAt="RECORD_REPLENISHMENT".equals(op)?utc(body.get("actualAt"),"actualAt"):null;
            countedAt="RECORD_COUNT".equals(op)?utc(body.get("countedAt"),"countedAt"):null;
            if("ADOPT_STOCK".equals(op)&&assignedUserId==null||"RESOLVE_INTAKE".equals(op)&&(intakeId==null||actionId==null||countChoice==null))throw WorkflowException.invalid("Explicit stock review fields are required.");
            if("INCLUDED_IN_COUNT".equals(countChoice)&&(countId==null||countVersion==null))throw WorkflowException.invalid("Select the reviewed current count and its version.");
        }
        public String payload(Long stockId)  {
            return WorkflowData.json(WorkflowData.map("operation",operation,"stockId",stockId,"body",body));
        }
    }
    public static StockCommand parse(String operation,Map<String,Object> raw)  {
        return new StockCommand(operation,raw);
    }
    public static BigDecimal decimal(Object raw,boolean zero)  {
        if(!(raw instanceof String)||!((String)raw).matches("(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,3})?"))throw WorkflowException.invalid("Use an explicit decimal string with at most three places.");
        BigDecimal q=new BigDecimal((String)raw).setScale(3);
        if(q.signum()<0||!zero&&q.signum()==0)throw WorkflowException.invalid("Quantity must be positive.");
        return q;
    }
    public static BigDecimal exact(BigDecimal q)  {
        if(q==null)return null;
        try  {
            q=q.setScale(3,RoundingMode.UNNECESSARY);
        }
        catch(ArithmeticException e)  {
            throw WorkflowException.invalid("Quantity exceeds three decimal places.");
        }
        if(q.precision()>12)throw WorkflowException.invalid("Quantity exceeds stock precision.");
        return q;
    }
    private static Long optionalId(Map<String,Object> m,String key)  {
        return m.get(key)==null?null:id(m.get(key),key);
    }
    public static void requireMinimalReceipt(Map<String,Object> value)  {
        try  {
            if(value==null||!value.keySet().equals(RECEIPT_KEYS))throw new IllegalArgumentException();
            for(String key:Arrays.asList("stockId","eventId","cycle","workId"))id(value.get(key),key);
            integer(value.get("version"),"version",0,9007199254740991L);
            for(String key:Arrays.asList("countId","resolutionId"))if(value.get(key)!=null)id(value.get(key),key);
            if(!Arrays.asList("OPEN","RESOLVED","NOT_APPLICABLE","CANCELLED_MISTAKE").contains(value.get("state")))throw new IllegalArgumentException();
        }
        catch(RuntimeException invalid)  {
            throw new WorkflowException(409,"RECEIPT_INVALID","Stored stock receipt is invalid.");
        }
    }
    public static class RecordedIntakeAction  {
        public final long actionId,intakeId,executionEventId,patientId,medicationId,actorId;
        public final Instant actualAt;
        public final BigDecimal quantity;
        public final String unit,attribution;
        public RecordedIntakeAction(long actionId,long intakeId,long executionEventId,long patientId,long medicationId,long actorId,Instant actualAt,BigDecimal quantity,String unit,String attribution)  {
            this.actionId=id(actionId,"actionId");
            this.intakeId=id(intakeId,"intakeId");
            this.executionEventId=id(executionEventId,"executionEventId");
            this.patientId=id(patientId,"patientId");
            this.medicationId=id(medicationId,"medicationId");
            this.actorId=id(actorId,"actorId");
            this.actualAt=actualAt;
            this.quantity=exact(quantity);
            this.unit=unit;
            this.attribution=attribution;
            if(quantity!=null&&quantity.signum()<=0)throw WorkflowException.invalid("Actual quantity must be positive.");
        }
        public Map<String,Object> values()  {
            return WorkflowData.map("actionId",actionId,"intakeId",intakeId,"executionEventId",executionEventId,"patientId",patientId,"medicationId",medicationId,"actorId",actorId,"actualAt",actualAt==null?null:actualAt.toString(),"quantity",quantity==null?null:quantity.toPlainString(),"unit",unit,"attribution",attribution);
        }
    }
    public static final class IntakeCorrection  {
        public final RecordedIntakeAction action;
        public final long originalActionId,correctionActionId;
        public final String commandKey,reason,countChoice,historicalUnit;
        public final Long countId,countVersion;
        public IntakeCorrection(RecordedIntakeAction action,long originalActionId,long correctionActionId,String commandKey,String reason,String countChoice,Long countId,Long countVersion,String historicalUnit)  {
            this.action=Objects.requireNonNull(action);
            this.originalActionId=id(originalActionId,"originalActionId");
            this.correctionActionId=id(correctionActionId,"correctionActionId");
            this.commandKey=WorkflowCommandExecutor.validateKey(commandKey);
            this.reason=text(reason,"reason",1000);
            this.countChoice=countChoice;
            this.countId=countId;
            this.countVersion=countVersion;
            this.historicalUnit=historicalUnit;
            if(action.actionId!=correctionActionId)throw WorkflowException.invalid("Correction action identity differs.");
            if(countChoice!=null&&!Arrays.asList("ACTUAL_AMOUNT","INCLUDED_IN_COUNT").contains(countChoice))throw WorkflowException.invalid("Select an explicit count choice.");
        }
    }
    public static final class ConsumptionResult  {
        public final String outcome,reason;
        public final Long resolutionId,stockEventId,originalMovementId,compensationMovementId;
        public ConsumptionResult(String outcome,Long resolutionId,Long stockEventId,Long originalMovementId,Long compensationMovementId,String reason)  {
            this.outcome=outcome;
            this.resolutionId=resolutionId;
            this.stockEventId=stockEventId;
            this.originalMovementId=originalMovementId;
            this.compensationMovementId=compensationMovementId;
            this.reason=reason;
        }
    }
}
