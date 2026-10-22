package org.familyhealthcare.dto;

import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.annotation.JSONType;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection;
import org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Arrays;

/** Immutable display copy of an admitted observation; never access or mutation authority. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JSONType(serializeFeatures = JSONWriter.Feature.WriteNulls)
public final class MedicalRecordReviewSummary {
    private final String availability;
    private final String participation;
    private final Boolean automaticInputEligible;
    private final String sourceStatus;
    private final Channel source;
    private final Channel doctor;
    private final String qualificationSemantics;
    private final String checkedAt;

    private MedicalRecordReviewSummary(String availability, String participation, Boolean eligible,
            String status, Channel source, Channel doctor, String semantics, String checkedAt) {
        this.availability=availability; this.participation=participation; automaticInputEligible=eligible;
        sourceStatus=status; this.source=source; this.doctor=doctor; qualificationSemantics=semantics; this.checkedAt=checkedAt;
    }
    public static MedicalRecordReviewSummary fromObservedState(MedicalRecordValidityProjection.State state) {
        if(state==null||!state.reviewObserved)return null;
        boolean available=state.reviewSummaryMetadataAvailable();
        if(state.checkedAt==null||(state.qualificationSemantics!=MedicalReviewQualificationReader.Semantics.CURRENT_CHECK
                &&state.qualificationSemantics!=MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT))throw integrity();
        positive(state.id);
        String semantics=state.qualificationSemantics.name(), checked=state.checkedAt.toString();
        if(!available)return new MedicalRecordReviewSummary("UNAVAILABLE",null,null,"SOURCE_UNAVAILABLE",null,null,semantics,checked);
        if(state.patientId!=null)positive(state.patientId);
        if(state.ownerId!=null)positive(state.ownerId);
        if(state.managed?!Arrays.asList("ACTIVE","INVALIDATED").contains(state.validity):!"UNTRACKED".equals(state.validity))throw integrity();
        if(state.doctor!=null&&state.patientId==null)throw integrity();
        Channel source=channel(state.source,false,state.version), doctor=channel(state.doctor,true,state.version);
        boolean participating=source!=null||doctor!=null;
        boolean active=!state.managed||"ACTIVE".equals(state.validity);
        boolean eligible=active&&(participating?source!=null&&"APPROVED".equals(source.state):!state.managed||"VERIFIED".equals(state.review));
        String status=!active?"SOURCE_INVALIDATED":!participating?(!state.managed?"UNTRACKED":eligible?"CURRENT":"SOURCE_REVIEW_REQUIRED"):
                eligible?"CURRENT":source!=null&&"SUPERSEDED".equals(source.state)?"SOURCE_CHANGED":"SOURCE_REVIEW_REQUIRED";
        if(state.reviewParticipating!=participating||state.automaticInputEligible!=eligible||!status.equals(state.status()))throw integrity();
        String participation=source==null?(doctor==null?"NONE":"DOCTOR_ONLY"):(doctor==null?"SOURCE_ONLY":"BOTH");
        return new MedicalRecordReviewSummary("AVAILABLE",participation,state.automaticInputEligible,state.status(),source,doctor,semantics,checked);
    }
    private static Channel channel(MedicalReviewQualificationReader.ChannelFacts facts, boolean doctor, long version) {
        if(facts==null)return null;
        positive(facts.contentVersion);positive(facts.decisionVersion);positive(facts.cycle);positive(facts.actorId);
        if(facts.contentVersion!=version||facts.decisionVersion==1&&facts.cycle!=1
                ||!Arrays.asList("ADMIN","ASSIGNED_DOCTOR","OWNER","DELEGATE").contains(facts.actorKind))throw integrity();
        boolean decision="APPROVED".equals(facts.state)&&"APPROVE".equals(facts.operation)
                ||"REJECTED".equals(facts.state)&&"REJECT".equals(facts.operation);
        boolean pending="PENDING".equals(facts.state)&&("RESUBMIT".equals(facts.operation)&&facts.decisionVersion>1&&facts.cycle>1
                ||doctor&&"REQUEST".equals(facts.operation)&&facts.decisionVersion==1&&facts.cycle==1);
        boolean changed="SUPERSEDED".equals(facts.state)&&"CONTENT_CHANGED".equals(facts.operation)&&facts.contentVersion>1&&facts.decisionVersion>1;
        if(!(decision||pending||changed)||doctor&&decision&&(facts.decisionVersion==1
                ||!Arrays.asList("ADMIN","ASSIGNED_DOCTOR").contains(facts.actorKind)))throw integrity();
        if(facts.occurredAt==null||!facts.occurredAt.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}"))throw integrity();
        try{LocalDateTime.parse(facts.occurredAt,DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS").withResolverStyle(ResolverStyle.STRICT));}
        catch(java.time.DateTimeException invalid){throw integrity();}
        return new Channel(facts.state,facts.contentVersion,facts.decisionVersion,facts.cycle,facts.operation,facts.actorId,facts.actorKind,facts.occurredAt);
    }
    private static void positive(long value){if(value<1||value>9007199254740991L)throw integrity();}
    private static WorkflowException integrity(){return new WorkflowException(409,"MEDICAL_REVIEW_INTEGRITY","Medical review metadata is inconsistent.");}
    public String getAvailability(){return availability;}
    public String getParticipation(){return participation;}
    public Boolean getAutomaticInputEligible(){return automaticInputEligible;}
    public String getSourceStatus(){return sourceStatus;}
    public Channel getSource(){return source;}
    public Channel getDoctor(){return doctor;}
    public String getQualificationSemantics(){return qualificationSemantics;}
    public String getCheckedAt(){return checkedAt;}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JSONType(serializeFeatures = JSONWriter.Feature.WriteNulls)
    public static final class Channel {
        private final String state;
        private final long contentVersion;
        private final long decisionVersion;
        private final long cycle;
        private final String operation;
        private final long actorId;
        private final String actorKind;
        private final String occurredAt;
        private Channel(String state,long contentVersion,long decisionVersion,long cycle,String operation,long actorId,String actorKind,String occurredAt){
            this.state=state;this.contentVersion=contentVersion;this.decisionVersion=decisionVersion;this.cycle=cycle;
            this.operation=operation;this.actorId=actorId;this.actorKind=actorKind;this.occurredAt=occurredAt;
        }
        public String getState(){return state;}
        public long getContentVersion(){return contentVersion;}
        public long getDecisionVersion(){return decisionVersion;}
        public long getCycle(){return cycle;}
        public String getOperation(){return operation;}
        public long getActorId(){return actorId;}
        public String getActorKind(){return actorKind;}
        public String getOccurredAt(){return occurredAt;}
    }
}
