package org.familyhealthcare.service.alertworkflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import static org.familyhealthcare.service.workflow.WorkflowData.id;

/** Append-only server-identity events. Callers own the effective transaction and aggregate lock. */
@Service
@ConditionalOnProperty(name="alert-workflow.enabled",havingValue="true")
public class AlertWorkflowEventStore {
    private final AlertWorkflowRepository repository;
    private final AlertWorkflowAuthorizationService authorization;
    public AlertWorkflowEventStore(AlertWorkflowRepository repository,AlertWorkflowAuthorizationService authorization) {
        this.repository=repository;this.authorization=authorization;
    }
    public long append(long actor,Map<String,Object> alert,String type,String from,String note,String entryMode,Instant occurredAt,Map<String,Object> payload) {
        long patient=id(alert.get("patientId"));
        return repository.appendEvent(id(alert.get("id")),patient,((Number)alert.get("cycleNo")).intValue(),type,
                authorization.actorSnapshot(actor,patient),from,(String)alert.get("workflowState"),note,entryMode,occurredAt,payload);
    }
}
