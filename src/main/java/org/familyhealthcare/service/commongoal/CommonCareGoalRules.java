package org.familyhealthcare.service.commongoal;

import org.familyhealthcare.service.workflow.WorkflowException;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.commongoal.CommonCareGoalContracts.*;

/** Human-entered arrangements only. These relationship rules never grant source access. */
final class CommonCareGoalRules {
    private CommonCareGoalRules() { }

    static boolean responsible(Goal goal, boolean nomineeEligible) {
        return nomineeEligible && goal.getAcceptedBy()!=null
                && goal.getAcceptedBy().equals(goal.getContent().getNomineeId());
    }
    static boolean completionReady(Goal goal, boolean nomineeEligible, Map<String,Object> progress, Map<String,Object> feedback) {
        return goal.getState()==State.ACTIVE && responsible(goal,nomineeEligible) && progress!=null && feedback!=null
                && !FeedbackSource.NOT_OBTAINED.name().equals(feedback.get("feedbackSource"));
    }
    static List<String> actions(long actor, long owner, Goal goal, boolean writeEnabled, boolean nomineeEligible,
                                Map<String,Object> progress, Map<String,Object> feedback) {
        List<String> result=new ArrayList<>();
        if (!writeEnabled) return result;
        boolean ownerActor=actor==owner, proposed=goal.getState()==State.PROPOSED, active=goal.getState()==State.ACTIVE;
        if (goal.isOpen() && (ownerActor || proposed && actor==goal.getCreatorId())) {
            result.add(Operation.REVISE.name()); result.add(Operation.CANCEL.name());
        }
        if (proposed && Objects.equals(goal.getContent().getNomineeId(),actor) && nomineeEligible) {
            result.add(Operation.ACCEPT.name()); result.add(Operation.DECLINE.name());
        }
        boolean acceptedActor=responsible(goal,nomineeEligible) && Objects.equals(goal.getAcceptedBy(),actor);
        if (active && acceptedActor) result.add(Operation.STEP_BACK.name());
        if (active && (ownerActor || acceptedActor)) {
            result.add(Operation.ADD_PROGRESS.name()); result.add(Operation.ADD_FEEDBACK.name());
        }
        if (ownerActor && completionReady(goal,nomineeEligible,progress,feedback)) result.add(Operation.COMPLETE.name());
        if (ownerActor && !goal.isOpen()) result.add(Operation.REOPEN.name());
        return Collections.unmodifiableList(result);
    }
    static void nomination(long actor, long owner, Goal previous, Content content) {
        Long next=content.getNomineeId(), old=previous==null?null:previous.getContent().getNomineeId();
        if (actor==owner) return;
        // A creator may clarify an unaccepted proposal without replacing its other nominee.
        if (old!=null && old!=actor) { if (!old.equals(next)) throw relation(); }
        else if (next!=null && next!=actor) throw relation();
    }
    static Goal transition(long actor, long owner, Command command, Goal before, boolean nomineeEligible,
                           Map<String,Object> progress, Map<String,Object> feedback, Instant now) {
        if (before.getVersion()!=command.getExpectedVersion())
            throw new WorkflowException(409,"COMMON_GOAL_VERSION_CONFLICT","This goal changed. Refresh before making a new decision.");
        Operation operation=command.getOperation();
        if (operation==Operation.COMPLETE && actor==owner && before.getState()==State.ACTIVE
                && !completionReady(before,nomineeEligible,progress,feedback))
            throw new WorkflowException(409,"COMMON_GOAL_COMPLETION_NOT_READY","Completion needs a currently eligible accepted responsible account, progress in this agreement cycle and latest obtained feedback.");
        if (!actions(actor,owner,before,true,nomineeEligible,progress,feedback).contains(operation.name()))
            throw new WorkflowException(403,"COMMON_GOAL_ACTION_UNAVAILABLE","This action is not available to the current account for this goal.");
        Content content=before.getContent(); State state=before.getState(); long cycle=before.getCycle();
        Long accepted=before.getAcceptedBy(); Instant acceptedAt=before.getAcceptedAt();
        switch (operation) {
            case REVISE:
                nomination(actor,owner,before,command.getContent());
                if (content.equals(command.getContent())) throw new WorkflowException(409,"COMMON_GOAL_NO_CHANGE","The proposed content has not changed.");
                content=command.getContent(); state=State.PROPOSED; cycle=next(cycle); accepted=null; acceptedAt=null; break;
            case ACCEPT: state=State.ACTIVE; accepted=actor; acceptedAt=now; break;
            case DECLINE: case STEP_BACK: case REOPEN:
                content=content.nominate(null); state=State.PROPOSED; cycle=next(cycle); accepted=null; acceptedAt=null; break;
            case COMPLETE: state=State.COMPLETED; break;
            case CANCEL: state=State.CANCELLED; break;
            case ADD_PROGRESS: case ADD_FEEDBACK: break;
            default: throw invalid();
        }
        return new Goal(before.getId(),before.getPatientId(),before.getCreatorId(),content,state,next(before.getVersion()),cycle,
                accepted,acceptedAt,before.getCreatedAt(),now);
    }
    static WorkflowException relation() {
        return new WorkflowException(403,"COMMON_GOAL_RELATION_REQUIRED","Only the current record owner can nominate another account or replace another nominee.");
    }
}
