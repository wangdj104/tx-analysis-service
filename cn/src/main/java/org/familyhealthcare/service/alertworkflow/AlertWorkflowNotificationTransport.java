package org.familyhealthcare.service.alertworkflow;
import org.familyhealthcare.service.NotificationDeliveryService;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryAttempt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
/** Alert-domain transport shares established outcome semantics without sharing feature enablement. */
public interface AlertWorkflowNotificationTransport {
    DeliveryOutcome send(long channel,long recipient,String event,String title,String path);
    default DeliveryAttempt attempt(long channel,long recipient,String event,String title,String path){return new DeliveryAttempt(send(channel,recipient,event,title,path),false);}
    @Component
    @ConditionalOnProperty(name="alert-workflow.enabled",havingValue="true")
    final class ExistingChannels implements AlertWorkflowNotificationTransport {
        private final NotificationDeliveryService delivery;
        public ExistingChannels(NotificationDeliveryService delivery){this.delivery=delivery;}
        public DeliveryOutcome send(long channel,long recipient,String event,String title,String path){return attempt(channel,recipient,event,title,path).getOutcome();}
        @Override public DeliveryAttempt attempt(long channel,long recipient,String event,String title,String path){return delivery.deliverAlertWorkflowAttempt(channel,recipient,event,title,path);}
    }
}
