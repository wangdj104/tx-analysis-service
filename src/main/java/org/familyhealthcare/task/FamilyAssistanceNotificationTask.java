package org.familyhealthcare.task;
import org.familyhealthcare.service.familyassistance.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component public class FamilyAssistanceNotificationTask {
    private final FamilyAssistanceService service;
    private final FamilyAssistanceNotificationWorker worker;
    private final FamilyAssistanceProperties properties;
    public FamilyAssistanceNotificationTask(FamilyAssistanceService service,FamilyAssistanceNotificationWorker worker,FamilyAssistanceProperties properties) {
        this.service=service;
        this.worker=worker;
        this.properties=properties;
    }
    @Scheduled(fixedDelay=60000)public void tick() {
        service.markDue(properties.now());
        if(properties.isEnabled())worker.tick(properties.now());
    }
}
