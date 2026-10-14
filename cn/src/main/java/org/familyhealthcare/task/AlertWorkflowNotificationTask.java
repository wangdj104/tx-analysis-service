package org.familyhealthcare.task;
import org.familyhealthcare.service.alertworkflow.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** Independently opt-in, no channel setup and no business transaction around transport. */
@Component
@ConditionalOnProperty(name="alert-workflow.enabled",havingValue="true")
public class AlertWorkflowNotificationTask {
    private final AlertWorkflowNotificationWorker worker;private final AlertWorkflowProperties properties;
    public AlertWorkflowNotificationTask(AlertWorkflowNotificationWorker worker,AlertWorkflowProperties properties){this.worker=worker;this.properties=properties;}
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void run(){if(properties.isEnabled())worker.tick(properties.now());}
}
