package org.familyhealthcare.service.commongoal;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.commongoal.CommonCareGoalContracts.*;

/** One proxied transaction owns the goal, immutable event and fixed command receipt. */
@Service
public class CommonCareGoalService {
    private final JdbcTemplate jdbc;
    private final CommonCareGoalRepository repository;
    private final CommonCareGoalAuthorization authorization;
    private final CommonCareGoalSchema schema;
    private final CommonCareGoalProperties properties;
    public CommonCareGoalService(JdbcTemplate jdbc, CommonCareGoalRepository repository, CommonCareGoalAuthorization authorization,
                                 CommonCareGoalSchema schema, CommonCareGoalProperties properties) {
        this.jdbc=jdbc; this.repository=repository; this.authorization=authorization; this.schema=schema; this.properties=properties;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor, String json) {
        WorkflowData.mutationTransaction(jdbc);
        Command command=CommonCareGoalContracts.command(json);
        authorization.requireWrite(actor,command.getPatientId());
        CommonCareGoalSchema.Status status=jdbc.execute((ConnectionCallback<CommonCareGoalSchema.Status>)schema::inspectStorage);
        schema.requireReadable(status);
        CommonCareGoalAuthorization.Admission admission=authorization.admit(actor,command,repository);
        Goal before=authorization.lockGoal(admission,actor,command,repository);
        Instant now=properties.now();
        return authorization.executeLocked(jdbc,admission,actor,command,now,()->{
            // Only the genuinely new reservation can enter this callback. Exact replay
            // never applies a current state, nominee, expectedVersion or feature check.
            schema.requireNewCommands(status,properties.isEnabled());
            long patient=command.getPatientId(), owner=authorization.recordOwner(patient);
            if (command.getOperation()==Operation.CREATE) {
                CommonCareGoalRules.nomination(actor,owner,null,command.getContent());
                requireNominee(command.getContent(),patient);
                authorization.requireNomineeBeforeCommit(admission,command.getContent().getNomineeId());
                Goal goal=repository.create(patient,actor,command.getContent(),now);
                repository.append(command,actor,null,goal,now);
                return receipt(goal,command.getOperation());
            }
            if (before==null) throw integrity();
            boolean eligible=before.getContent().getNomineeId()!=null && authorization.canWrite(before.getContent().getNomineeId(),patient);
            Map<String,Object> progress=null, feedback=null;
            if (command.getOperation()==Operation.COMPLETE) {
                progress=repository.latestEvent(patient,before.getId(),before.getCycle(),Operation.ADD_PROGRESS);
                feedback=repository.latestEvent(patient,before.getId(),before.getCycle(),Operation.ADD_FEEDBACK);
            }
            Goal after=CommonCareGoalRules.transition(actor,owner,command,before,eligible,progress,feedback,now);
            if (command.getOperation()==Operation.REVISE) requireNominee(after.getContent(),patient);
            authorization.requireLockedGoal(admission.getAuthority(),actor,after);
            if (command.getOperation()==Operation.REVISE || command.getOperation()==Operation.COMPLETE)
                authorization.requireNomineeBeforeCommit(admission,after.getContent().getNomineeId());
            repository.append(command,actor,before,after,now);
            repository.save(before,after);
            return receipt(after,command.getOperation());
        });
    }
    private void requireNominee(Content content, long patient) {
        if (content.getNomineeId()!=null && !authorization.canWrite(content.getNomineeId(),patient))
            throw new WorkflowException(409,"COMMON_GOAL_NOMINEE_UNAVAILABLE","The nominated account cannot currently take responsibility for this patient. Refresh the available participants.");
    }
}
