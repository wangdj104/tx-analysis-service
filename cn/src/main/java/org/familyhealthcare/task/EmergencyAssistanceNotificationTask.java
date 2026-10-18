package org.familyhealthcare.task;
import org.familyhealthcare.service.emergencyassistance.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component public class EmergencyAssistanceNotificationTask {
    private final EmergencyAssistanceService service;
    private final EmergencyAssistanceNotificationWorker worker;
    private final EmergencyAssistanceProperties properties;
    public EmergencyAssistanceNotificationTask(EmergencyAssistanceService service,EmergencyAssistanceNotificationWorker worker,
    EmergencyAssistanceProperties properties) {
        this.service=service;
        this.worker=worker;
        this.properties=properties;
    }
    @Scheduled(fixedDelay=60000)public void tick() {
        if(properties.isEnabled())worker.tick(properties.now());
    }
}
