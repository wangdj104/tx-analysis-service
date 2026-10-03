package org.familyhealthcare.task;

import org.familyhealthcare.service.careplan.CarePlanNotificationWorker;
import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in scheduler; the worker owns durable claims and current authority checks. */
@Component
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
public class CarePlanNotificationTask {
    private final CarePlanNotificationWorker worker;
    private final CarePlanProperties properties;
    public CarePlanNotificationTask(CarePlanNotificationWorker worker,CarePlanProperties properties) {this.worker=worker;this.properties=properties;}
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void run(){if(properties.isEnabled())worker.tick(properties.now());}
}
