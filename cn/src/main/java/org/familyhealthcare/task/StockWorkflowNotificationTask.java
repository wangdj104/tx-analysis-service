package org.familyhealthcare.task;
import org.familyhealthcare.service.stockworkflow.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** Generic delivery only; this does not schedule doses or apply verified orders. */
@Component public class StockWorkflowNotificationTask  {
    private final StockWorkflowNotificationWorker worker;
    private final StockWorkflowProperties properties;
    public StockWorkflowNotificationTask(StockWorkflowNotificationWorker worker,StockWorkflowProperties properties)  {
        this.worker=worker;
        this.properties=properties;
    }
    @Scheduled(fixedDelay=60000) public void tick()  {
        if(properties.isEnabled())worker.tick(properties.now());
    }
}
