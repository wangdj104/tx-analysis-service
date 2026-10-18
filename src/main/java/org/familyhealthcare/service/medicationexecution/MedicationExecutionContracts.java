package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.WorkflowCommandExecutor;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
/** Immutable, finite manual transcription contract. No dosage interpretation or client clinical authority. */
public final class MedicationExecutionContracts {
    private MedicationExecutionContracts() {
    }
    public static final Set<String> OPERATIONS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
    "CREATE_RECONCILIATION","NEW_RECONCILIATION","REVISE_RECONCILIATION","VERIFY_RECONCILIATION","RETURN_RECONCILIATION","CANCEL_RECONCILIATION")));
    public static final class CommandMeta {
        public final String commandKey;
        public final long expectedVersion,patientId;
        private CommandMeta(Map<String,Object> raw) {
            commandKey=WorkflowCommandExecutor.validateKey(text(raw.get("commandKey"),"commandKey",36));
            expectedVersion=integer(raw.get("expectedVersion"),"expectedVersion",0,9007199254740991L);
            patientId=id(raw.get("patientId"),"patientId");
        }
    }
    public static final class SourceReference {
        public final String kind,provenance;
        public final Long prescriptionId,itemId;
        public final Integer version;
        private SourceReference(Map<String,Object> raw) {
            kind=choice(raw.get("kind"),"kind","MANUAL","PRESCRIPTION");
            if("MANUAL".equals(kind)) {
                fields(raw,"kind","provenance");
                provenance=text(raw.get("provenance"),"provenance",1000);
                prescriptionId=null;
                itemId=null;
                version=null;
            }
            else {
                fields(raw,"kind","prescriptionId","itemId","version");
                provenance=null;
                prescriptionId=id(raw.get("prescriptionId"),"prescriptionId");
                itemId=id(raw.get("itemId"),"itemId");
                version=(int)integer(raw.get("version"),"version",1,Integer.MAX_VALUE);
            }
        }
        public Map<String,Object> values() {
            return "MANUAL".equals(kind)?WorkflowData.map("kind",kind,"provenance",provenance):WorkflowData.map("kind",kind,"prescriptionId",prescriptionId,"itemId",itemId,"version",version);
        }
    }
    public static final class StructuredMapping {
        public final String action,quantity,unit,scheduleZone;
        public final List<String> times;
        public final List<Integer> weekdays;
        public final LocalDateTime effectiveLocal,endLocal;
        public final int effectiveOffsetSeconds;
        public final Integer endOffsetSeconds;
        public final Instant effectiveAt,endAt;
        private StructuredMapping(Map<String,Object> raw) {
            fields(raw,"action","quantity","unit","times","weekdays","scheduleZone","effectiveLocal","effectiveOffsetSeconds","effectiveAt","endLocal","endOffsetSeconds","endAt");
            action=choice(raw.get("action"),"action","CHANGE","STOP");
            if("CHANGE".equals(action)) {
                quantity=quantity(raw.get("quantity"));
                unit=text(raw.get("unit"),"unit",20);
                Object t=raw.get("times");
                if(!(t instanceof List)||((List<?>)t).isEmpty()||((List<?>)t).size()>24)throw invalid("Enter 1 to 24 explicit times.");
                SortedSet<String> ts=new TreeSet<>();
                for(Object value:(List<?>)t) {
                    String s=text(value,"time",5);
                    if(!s.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")||!ts.add(s))throw invalid("Invalid or repeated time.");
                }
                times=Collections.unmodifiableList(new ArrayList<>(ts));
                Object w=raw.get("weekdays");
                if(!(w instanceof List)||((List<?>)w).isEmpty()||((List<?>)w).size()>7)throw invalid("Enter explicit weekdays.");
                SortedSet<Integer> ws=new TreeSet<>();
                for(Object value:(List<?>)w) {
                    long day=integer(value,"weekday",1,7);
                    if(!ws.add((int)day))throw invalid("Repeated weekday.");
                }
                weekdays=Collections.unmodifiableList(new ArrayList<>(ws));
            }
            else {
                if(raw.containsKey("quantity")||raw.containsKey("unit")||raw.containsKey("times")||raw.containsKey("weekdays"))throw invalid("STOP has no dose or reminder times.");
                quantity=null;
                unit=null;
                times=Collections.emptyList();
                weekdays=Collections.emptyList();
            }
            scheduleZone=text(raw.get("scheduleZone"),"scheduleZone",64);
            ZoneId zone;
            try {
                zone=ZoneId.of(scheduleZone);
            }
            catch(DateTimeException ex) {
                throw invalid("Enter an explicit time zone.");
            }
            effectiveLocal=local(raw.get("effectiveLocal"),"effectiveLocal");
            effectiveOffsetSeconds=(int)integer(raw.get("effectiveOffsetSeconds"),"effectiveOffsetSeconds",-64800,64800);
            effectiveAt=utc(raw.get("effectiveAt"),"effectiveAt");
            validateTime(zone,effectiveLocal,effectiveOffsetSeconds,effectiveAt);
            boolean ending=raw.containsKey("endAt")||raw.containsKey("endLocal")||raw.containsKey("endOffsetSeconds");
            if(ending) {
                endLocal=local(raw.get("endLocal"),"endLocal");
                endOffsetSeconds=(int)integer(raw.get("endOffsetSeconds"),"endOffsetSeconds",-64800,64800);
                endAt=utc(raw.get("endAt"),"endAt");
                validateTime(zone,endLocal,endOffsetSeconds,endAt);
                if(!endAt.isAfter(effectiveAt))throw invalid("End must follow the effective time.");
            }
            else {
                endLocal=null;
                endOffsetSeconds=null;
                endAt=null;
            }
        }
        public Map<String,Object> values() {
            Map<String,Object> out=WorkflowData.map("action",action,"scheduleZone",scheduleZone,"effectiveLocal",effectiveLocal.toString(),"effectiveOffsetSeconds",effectiveOffsetSeconds,"effectiveAt",effectiveAt.toString());
            if(quantity!=null)out.putAll(WorkflowData.map("quantity",quantity,"unit",unit,"times",times,"weekdays",weekdays));
            if(endAt!=null)out.putAll(WorkflowData.map("endLocal",endLocal.toString(),"endOffsetSeconds",endOffsetSeconds,"endAt",endAt.toString()));
            return out;
        }
    }
    public static final class ReconciliationCommand {
        public final String operation,reason,revisionHash;
        public final CommandMeta meta;
        public final Long medicationId,selectedVerifierId,reconciliationId,revisionId;
        public final SourceReference source;
        public final StructuredMapping mapping;
        private ReconciliationCommand(String operation,Map<String,Object> raw) {
            if(!OPERATIONS.contains(operation))throw invalid("Unsupported reconciliation action.");
            this.operation=operation;
            fields(raw,"commandKey","expectedVersion","patientId","body");
            meta=new CommandMeta(raw);
            Map<String,Object> body=object(raw.get("body"),"body");
            boolean draft=Arrays.asList("CREATE_RECONCILIATION","NEW_RECONCILIATION","REVISE_RECONCILIATION").contains(operation);
            if(draft) {
                if("REVISE_RECONCILIATION".equals(operation)) {
                    fields(body,"reconciliationId","selectedVerifierId","source","mapping");
                    reconciliationId=id(body.get("reconciliationId"),"reconciliationId");
                    medicationId=null;
                }
                else {
                    fields(body,"medicationId","selectedVerifierId","source","mapping");
                    medicationId=id(body.get("medicationId"),"medicationId");
                    reconciliationId=null;
                }
                selectedVerifierId=body.get("selectedVerifierId")==null?null:id(body.get("selectedVerifierId"),"selectedVerifierId");
                source=new SourceReference(object(body.get("source"),"source"));
                mapping=new StructuredMapping(object(body.get("mapping"),"mapping"));
                revisionId=null;
                revisionHash=null;
                reason=null;
            }
            else {
                fields(body,"reconciliationId","revisionId","revisionHash","reason");
                reconciliationId=id(body.get("reconciliationId"),"reconciliationId");
                revisionId=id(body.get("revisionId"),"revisionId");
                revisionHash=text(body.get("revisionHash"),"revisionHash",64);
                if(!revisionHash.matches("[0-9a-f]{64}"))throw invalid("Invalid revision hash.");
                if("VERIFY_RECONCILIATION".equals(operation)&&body.containsKey("reason"))throw invalid("Verification does not accept extra text.");
                reason="VERIFY_RECONCILIATION".equals(operation)?null:text(body.get("reason"),"reason",1000);
                medicationId=null;
                selectedVerifierId=null;
                source=null;
                mapping=null;
            }
        }
        public String payload(Long target) {
            Map<String,Object> body=new LinkedHashMap<>();
            if(source!=null) {
                if(medicationId!=null)body.put("medicationId",medicationId);
                if(reconciliationId!=null)body.put("reconciliationId",reconciliationId);
                body.put("selectedVerifierId",selectedVerifierId);
                body.put("source",source.values());
                body.put("mapping",mapping.values());
            }
            else body.putAll(WorkflowData.map("reconciliationId",reconciliationId,"revisionId",revisionId,"revisionHash",revisionHash,"reason",reason));
            return WorkflowData.json(WorkflowData.map("operation",operation,"target",target,"patientId",meta.patientId,"body",body));
        }
    }
    public static final Set<String> BOUNDARY_OPERATIONS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("SCHEDULE_REPLACEMENT","CANCEL_SCHEDULE")));
    /** A finite read-only preview, echoed exactly by the later scheduling decision. */
    public static final class ReplacementPreview {
        public final long executionVersion,reconciliationId,revisionId;
        public final String revisionHash,fingerprint;
        public final Instant effectiveAt;
        public final List<Long> sortedOrderIds,sortedReminderIds;
        private ReplacementPreview(Map<String,Object> raw) {
            fields(raw,"executionVersion","reconciliationId","revisionId","revisionHash","effectiveAt","sortedOrderIds","sortedReminderIds","fingerprint");
            executionVersion=integer(raw.get("executionVersion"),"executionVersion",0,9007199254740991L);
            reconciliationId=id(raw.get("reconciliationId"),"reconciliationId");revisionId=id(raw.get("revisionId"),"revisionId");
            revisionHash=digest(raw.get("revisionHash"));fingerprint=digest(raw.get("fingerprint"));effectiveAt=utc(raw.get("effectiveAt"),"effectiveAt");
            sortedOrderIds=orderedIds(raw.get("sortedOrderIds"));sortedReminderIds=orderedIds(raw.get("sortedReminderIds"));
        }
        public Map<String,Object> values(){return WorkflowData.map("executionVersion",executionVersion,"reconciliationId",reconciliationId,"revisionId",revisionId,"revisionHash",revisionHash,"effectiveAt",effectiveAt.toString(),"sortedOrderIds",sortedOrderIds,"sortedReminderIds",sortedReminderIds,"fingerprint",fingerprint);}
    }
    public static final class BoundaryCommand {
        public final String operation,reason;
        public final CommandMeta meta;
        public final ReplacementPreview preview;
        public final Long reconciliationId;
        private BoundaryCommand(String operation,Map<String,Object> raw) {
            if(!BOUNDARY_OPERATIONS.contains(operation))throw invalid("Unsupported interval action.");
            fields(raw,"commandKey","expectedVersion","patientId","body");this.operation=operation;meta=new CommandMeta(raw);
            Map<String,Object> body=object(raw.get("body"),"body");
            if("SCHEDULE_REPLACEMENT".equals(operation)) {fields(body,"replacementPreview");preview=new ReplacementPreview(object(body.get("replacementPreview"),"replacementPreview"));reconciliationId=preview.reconciliationId;reason=null;}
            else {fields(body,"reconciliationId","reason");preview=null;reconciliationId=id(body.get("reconciliationId"),"reconciliationId");reason=text(body.get("reason"),"reason",1000);}
        }
        public String payload(long execution){return WorkflowData.json(WorkflowData.map("operation",operation,"executionId",execution,"patientId",meta.patientId,"body",preview==null?WorkflowData.map("reconciliationId",reconciliationId,"reason",reason):WorkflowData.map("replacementPreview",preview.values())));}
    }
    public static BoundaryCommand parseBoundary(String operation,Map<String,Object> raw){return new BoundaryCommand(operation,raw);}
    private static String digest(Object value){String s=text(value,"hash",64);if(!s.matches("[0-9a-f]{64}"))throw invalid("Invalid hash.");return s;}
    private static List<Long> orderedIds(Object value){if(!(value instanceof List)||((List<?>)value).size()>50)throw invalid("A bounded sorted identity list is required.");List<Long> out=new ArrayList<>();long previous=0;for(Object x:(List<?>)value){long n=id(x,"id");if(n<=previous)throw invalid("Identities must be sorted and unique.");out.add(n);previous=n;}return Collections.unmodifiableList(out);}
    public static final Set<String> INTAKE_OPERATIONS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("RECORD_TAKEN","RECORD_SKIPPED","SNOOZE","CORRECT_INTAKE","RECORD_CONTACT","END_INTAKE_UNRESOLVED","REOPEN_INTAKE_FOLLOW_UP")));
    public static final class IntakeCommand {
        public final String operation,status,attribution,amountBasis,unit,reason,reasonCode,historicalUnit,countChoice;
        public final CommandMeta meta;public final long intakeId;public final Long originalActionId,countId,countVersion;
        public final Instant actualAt;public final BigDecimal quantity;private final Map<String,Object> body;
        private IntakeCommand(String operation,Map<String,Object> raw){
            if(!INTAKE_OPERATIONS.contains(operation))throw invalid("Unsupported intake action.");fields(raw,"commandKey","expectedVersion","patientId","body");this.operation=operation;meta=new CommandMeta(raw);body=new LinkedHashMap<>(object(raw.get("body"),"body"));
            boolean fact=Arrays.asList("RECORD_TAKEN","RECORD_SKIPPED","CORRECT_INTAKE").contains(operation),correction="CORRECT_INTAKE".equals(operation);
            if(fact){if(correction)fields(body,"intakeId","status","actualAt","attribution","amountBasis","quantity","unit","originalActionId","reason","historicalUnit","countChoice","countId","countVersion");else fields(body,"intakeId","status","actualAt","attribution","amountBasis","quantity","unit");}
            else if("END_INTAKE_UNRESOLVED".equals(operation))fields(body,"intakeId","reasonCode","reason");else if("SNOOZE".equals(operation))fields(body,"intakeId");else fields(body,"intakeId","reason");
            intakeId=id(body.get("intakeId"),"intakeId");originalActionId=correction?id(body.get("originalActionId"),"originalActionId"):null;
            status=fact?(correction?choice(body.get("status"),"status","TAKEN","SKIPPED"):operation.substring(7)):null;
            if(fact&&body.containsKey("status")&&!status.equals(body.get("status")))throw invalid("Fact status differs from the operation.");
            actualAt=fact?utc(body.get("actualAt"),"actualAt"):null;attribution=fact?choice(body.get("attribution"),"attribution","SELF","ASSISTED"):null;
            amountBasis=fact?choice(body.get("amountBasis"),"amountBasis","ACTUAL","CONFIRMED_PLAN","UNKNOWN","NOT_APPLICABLE"):null;
            quantity=body.get("quantity")==null?null:org.familyhealthcare.service.stockworkflow.StockWorkflowContracts.decimal(body.get("quantity"),false);unit=body.get("unit")==null?null:text(body.get("unit"),"unit",20);
            if(fact&&"SKIPPED".equals(status)&&(!"NOT_APPLICABLE".equals(amountBasis)||quantity!=null||unit!=null))throw invalid("Skipped is a zero-quantity fact.");
            if(fact&&"TAKEN".equals(status)&&("NOT_APPLICABLE".equals(amountBasis)||"ACTUAL".equals(amountBasis)&&(quantity==null||unit==null)||"CONFIRMED_PLAN".equals(amountBasis)&&(quantity!=null||unit!=null)))throw invalid("Confirm a frozen plan, enter an explicit amount and unit, or retain unknown values.");
            if(fact&&"UNKNOWN".equals(amountBasis)&&quantity!=null&&unit!=null)throw invalid("Use actual amount evidence when both quantity and unit are known.");
            reason=correction||Arrays.asList("RECORD_CONTACT","END_INTAKE_UNRESOLVED","REOPEN_INTAKE_FOLLOW_UP").contains(operation)?text(body.get("reason"),"reason",1000):null;
            reasonCode="END_INTAKE_UNRESOLVED".equals(operation)?choice(body.get("reasonCode"),"reasonCode","NO_RESPONSE","CONTACT_FAILED","OUTCOME_UNKNOWN"):null;
            historicalUnit=body.get("historicalUnit")==null?null:text(body.get("historicalUnit"),"historicalUnit",20);
            countChoice=body.get("countChoice")==null?null:choice(body.get("countChoice"),"countChoice","ACTUAL_AMOUNT","INCLUDED_IN_COUNT");countId=body.get("countId")==null?null:id(body.get("countId"),"countId");countVersion=body.get("countVersion")==null?null:integer(body.get("countVersion"),"countVersion",0,9007199254740991L);
            if("INCLUDED_IN_COUNT".equals(countChoice)&&(countId==null||countVersion==null)||countChoice==null&&(countId!=null||countVersion!=null))throw invalid("Review the exact current count before choosing inclusion.");
        }
        public String payload(Long execution){return WorkflowData.json(WorkflowData.map("operation",operation,"executionId",execution,"patientId",meta.patientId,"body",body));}
    }
    public static final class PersonalStockFact {
        public final long medicationId;public final IntakeCommand command;
        private PersonalStockFact(Map<String,Object> raw){fields(raw,"commandKey","expectedVersion","patientId","body");Map<String,Object> body=new LinkedHashMap<>(object(raw.get("body"),"body"));medicationId=id(body.remove("medicationId"),"medicationId");String status=choice(body.get("status"),"status","TAKEN","SKIPPED");Map<String,Object> envelope=new LinkedHashMap<>(raw);envelope.put("body",body);command=new IntakeCommand(body.containsKey("originalActionId")?"CORRECT_INTAKE":"RECORD_"+status,envelope);if(command.meta.expectedVersion!=0)throw invalid("Personal creation requires a null target and version zero.");}
    }
    public static IntakeCommand parseIntake(String operation,Map<String,Object> raw){return new IntakeCommand(operation,raw);}
    public static PersonalStockFact parsePersonalStockFact(Map<String,Object> raw){return new PersonalStockFact(raw);}
    public static final Set<String> INTAKE_RECEIPT_KEYS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("executionId","version","eventId","state","cycle","intakeId","actionId","followUpState","stockOutcome","resolutionId","stockEventId","originalMovementId","compensationMovementId")));
    private static void requireIntakeReceipt(Map<String,Object> receipt){
        if(!INTAKE_RECEIPT_KEYS.equals(receipt.keySet()))throw invalid("Invalid intake receipt fields.");for(String key:Arrays.asList("executionId","eventId","intakeId"))id(receipt.get(key),key);integer(receipt.get("version"),"version",1,9007199254740991L);integer(receipt.get("cycle"),"cycle",1,Integer.MAX_VALUE);for(String key:Arrays.asList("actionId","resolutionId","stockEventId","originalMovementId","compensationMovementId"))if(receipt.get(key)!=null)id(receipt.get(key),key);choice(receipt.get("state"),"state","PENDING","SNOOZED","MISSED","TAKEN","SKIPPED","CANCELLED");choice(receipt.get("followUpState"),"followUpState","OPEN","ENDED_UNRESOLVED","FACT_RECORDED","NOT_APPLICABLE");choice(receipt.get("stockOutcome"),"stockOutcome","NOT_APPLICABLE","UNRECONCILED","APPLIED","COUNT_INCLUDED","NO_DELTA");
    }
    public static void requireMinimalReceipt(Map<String,Object> receipt) {
        Set<String> keys=new HashSet<>(Arrays.asList("executionId","version","eventId","state","cycle","reconciliationId","revisionId"));
        try {
            if(receipt!=null&&receipt.containsKey("intakeId")){requireIntakeReceipt(receipt);return;}
            if(receipt==null||!keys.equals(receipt.keySet()))throw invalid("Invalid receipt fields.");
            for(String key:Arrays.asList("executionId","eventId","reconciliationId","revisionId"))id(receipt.get(key),key);
            integer(receipt.get("version"),"version",1,9007199254740991L);
            integer(receipt.get("cycle"),"cycle",1,Integer.MAX_VALUE);
            choice(receipt.get("state"),"state","DRAFT","READY","RETURNED","CANCELLED","SOURCE_CHANGED","REPLACEMENT_CHANGED","TIME_UNRESOLVED","SCHEDULED","ACTIVE","SUPERSEDED","ENDED");
        }
        catch(WorkflowException invalid) {
            throw new WorkflowException(409,"RECEIPT_INVALID","The stored command receipt is invalid. Refresh and review the current record.");
        }
    }
    public static StructuredMapping parseMapping(Map<String,Object> raw) {
        return new StructuredMapping(raw);
    }
    public static SourceReference parseSource(Map<String,Object> raw) {
        return new SourceReference(raw);
    }
    public static ReconciliationCommand parse(String operation,Map<String,Object> raw) {
        return new ReconciliationCommand(operation,raw);
    }
    public static void fields(Map<String,?> raw,String...allowed) {
        if(raw==null||!new HashSet<>(Arrays.asList(allowed)).containsAll(raw.keySet()))throw invalid("Unknown request field.");
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value,String name) {
        if(!(value instanceof Map))throw invalid(name+" must be an object.");
        for(Object key:((Map<?,?>)value).keySet())if(!(key instanceof String))throw invalid("Invalid object key.");
        return (Map<String,Object>)value;
    }
    public static long id(Object value,String name) {
        return integer(value,name,1,9007199254740991L);
    }
    public static long integer(Object value,String name,long min,long max) {
        if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof java.math.BigInteger))throw invalid(name+" must be an exact integer.");
        try {
            long n=new BigDecimal(value.toString()).longValueExact();
            if(n<min||n>max)throw invalid(name+" is out of range.");
            return n;
        }
        catch(ArithmeticException ex) {
            throw invalid(name+" is out of range.");
        }
    }
    public static String text(Object value,String name,int max) {
        if(!(value instanceof String))throw invalid(name+" is required.");
        String s=((String)value).trim();
        if(s.isEmpty()||s.length()>max)throw invalid("Invalid "+name+".");
        return s;
    }
    public static String choice(Object value,String name,String...choices) {
        String s=text(value,name,64);
        if(!Arrays.asList(choices).contains(s))throw invalid("Unsupported "+name+".");
        return s;
    }
    public static Instant utc(Object value,String name) {
        String s=text(value,name,40);
        if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z"))throw invalid(name+" must be explicit UTC.");
        try {
            return Instant.parse(s);
        }
        catch(DateTimeException ex) {
            throw invalid("Invalid "+name+".");
        }
    }
    private static LocalDateTime local(Object value,String name) {
        String s=text(value,name,32);
        if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,6})?)?"))throw invalid("Invalid "+name+".");
        try {
            return LocalDateTime.parse(s);
        }
        catch(DateTimeException ex) {
            throw invalid("Invalid "+name+".");
        }
    }
    private static void validateTime(ZoneId zone,LocalDateTime local,int seconds,Instant utc) {
        ZoneOffset offset=ZoneOffset.ofTotalSeconds(seconds);
        if(!zone.getRules().getValidOffsets(local).contains(offset)||!local.toInstant(offset).equals(utc))throw invalid("TIME_UNRESOLVED: confirm the local time, zone, offset and UTC.");
    }
    private static String quantity(Object value) {
        if(!(value instanceof String)||!((String)value).matches("(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,3})?"))throw invalid("quantity must be a decimal string with up to three decimal places.");
        BigDecimal n=new BigDecimal((String)value);
        if(n.signum()<=0)throw invalid("quantity must be positive.");
        return n.setScale(3).toPlainString();
    }
    private static WorkflowException invalid(String message) {
        return WorkflowException.invalid(message);
    }
}
