package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.service.workflow.WorkflowException;
import java.util.Objects;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContentFingerprint.integrity;

/** Immutable completed past facts only. An old approval is not current source qualification. */
final class MedicalReviewReceipt {
    final long actorId, recordId, eventId, contentVersion, decisionVersion, cycle;
    final Channel channel;
    final Operation operation;
    final State state;
    final String generation, actorKind, appliedAt;
    private final String commandKey, requestHash;

    MedicalReviewReceipt(Long actorId, Long recordId, String channel, String commandKey, String generation,
                         String requestHash, Long eventId, String operation, Long contentVersion,
                         Long decisionVersion, Long cycle, String state, String actorKind, String appliedAt) {
        this.actorId=MedicalReviewContentFingerprint.id(actorId); this.recordId=MedicalReviewContentFingerprint.id(recordId);
        this.eventId=MedicalReviewContentFingerprint.id(eventId); this.contentVersion=MedicalReviewContentFingerprint.id(contentVersion);
        this.decisionVersion=MedicalReviewContentFingerprint.id(decisionVersion); this.cycle=MedicalReviewContentFingerprint.id(cycle);
        try {
            this.channel=Channel.valueOf(channel); this.operation=Operation.valueOf(operation); this.state=State.valueOf(state);
            if (this.operation!=Operation.REQUEST && this.operation!=Operation.APPROVE
                    && this.operation!=Operation.REJECT && this.operation!=Operation.RESUBMIT) throw integrity();
            requireEventFacts(generation,this.channel,this.operation,this.state,
                    this.decisionVersion==1 ? null : this.contentVersion,this.contentVersion,this.decisionVersion,this.cycle);
        } catch (IllegalArgumentException | NullPointerException | WorkflowException failure) { throw integrity(); }
        if (commandKey==null || !commandKey.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || requestHash==null || !requestHash.matches("[0-9a-f]{64}")) throw integrity();
        if (!"OWNER".equals(actorKind) && !"DELEGATE".equals(actorKind)
                && !"ASSIGNED_DOCTOR".equals(actorKind) && !"ADMIN".equals(actorKind)) throw integrity();
        if (this.channel==Channel.DOCTOR && (this.operation==Operation.APPROVE || this.operation==Operation.REJECT)
                && !"ASSIGNED_DOCTOR".equals(actorKind) && !"ADMIN".equals(actorKind)) throw integrity();
        if (appliedAt==null) throw integrity();
        MedicalReviewContentFingerprint.time(appliedAt);
        if (appliedAt.compareTo("1000-01-01T00:00:00.000000")<0) throw integrity();
        this.commandKey=commandKey; this.requestHash=requestHash; this.generation=generation;
        this.actorKind=actorKind; this.appliedAt=appliedAt;
    }
    void requireCommand(Command command) {
        if (actorId!=command.getActorId() || recordId!=command.getRecordId() || channel!=command.getChannel()
                || !commandKey.equals(command.getCommandKey())) throw integrity();
        if (classifyReplay(command,actorId,recordId,channel,commandKey,requestHash)==Replay.CONFLICT)
            throw new WorkflowException(409,"MEDICAL_REVIEW_COMMAND_CONFLICT","This command key was already used for a different medical review request.");
        if (operation!=command.getOperation() || contentVersion!=command.getExpectedContentVersion()
                || decisionVersion-1!=command.getExpectedDecisionVersion()) throw integrity();
    }
    // Finite internal key reuse only; no public projection, serialization or diagnostic text.
    String commandKey() { return commandKey; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof MedicalReviewReceipt)) return false;
        MedicalReviewReceipt r=(MedicalReviewReceipt)other;
        return actorId==r.actorId && recordId==r.recordId && eventId==r.eventId && contentVersion==r.contentVersion
                && decisionVersion==r.decisionVersion && cycle==r.cycle && channel==r.channel && operation==r.operation
                && state==r.state && generation.equals(r.generation) && actorKind.equals(r.actorKind)
                && appliedAt.equals(r.appliedAt) && commandKey.equals(r.commandKey) && requestHash.equals(r.requestHash);
    }
    @Override public int hashCode() {
        return Objects.hash(actorId,recordId,eventId,contentVersion,decisionVersion,cycle,channel,operation,state,
                generation,actorKind,appliedAt,commandKey,requestHash);
    }
}
