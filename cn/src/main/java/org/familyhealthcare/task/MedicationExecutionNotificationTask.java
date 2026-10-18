package org.familyhealthcare.task;
import org.familyhealthcare.service.medicationexecution.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** Generic delivery only; this does not schedule doses or apply verified orders. */
@Component
public class MedicationExecutionNotificationTask {
    private final MedicationExecutionNotificationWorker worker;
    private final MedicationExecutionProperties properties;
    public MedicationExecutionNotificationTask(MedicationExecutionNotificationWorker worker,MedicationExecutionProperties properties) {
        this.worker=worker;
        this.properties=properties;
    }
    @Scheduled(fixedDelay=60000) public void tick() {
        if(properties.isEnabled())worker.tick(properties.now());
    }
}
