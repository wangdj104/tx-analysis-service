package org.familyhealthcare.service.careplan;

import org.familyhealthcare.service.NotificationDeliveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A transport result is delivery acknowledgement, never proof that the recipient read it. */
public interface CarePlanNotificationTransport {
    enum DeliveryOutcome { DELIVERED, FAILED, UNKNOWN, NO_CHANNEL }
    DeliveryOutcome send(long channelId,String eventKey,String title,String relativePath);

    /** An old FAILED result does not establish that another attempt is safe. */
    default DeliveryAttempt attempt(long channelId,String eventKey,String title,String relativePath) {
        return new DeliveryAttempt(send(channelId,eventKey,title,relativePath),false);
    }
    final class DeliveryAttempt {
        private final DeliveryOutcome outcome;
        private final boolean retryable;
        public DeliveryAttempt(DeliveryOutcome outcome,boolean retryable) {
            this.outcome=java.util.Objects.requireNonNull(outcome,"outcome");
            if(retryable&&outcome!=DeliveryOutcome.FAILED)throw new IllegalArgumentException("Only an explicit failure can be retryable.");
            this.retryable=retryable;
        }
        public DeliveryOutcome getOutcome(){return outcome;}
        public boolean isRetryable(){return retryable;}
    }

    /** Only existing user-owned webhook providers; no new destination registration. */
    @Component
    @ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
    final class ExistingChannels implements CarePlanNotificationTransport {
        private final NotificationDeliveryService delivery;
        public ExistingChannels(NotificationDeliveryService delivery){this.delivery=delivery;}
        public DeliveryOutcome send(long channelId,String eventKey,String title,String relativePath){return delivery.deliverCarePlan(channelId,eventKey,title,relativePath);}
        @Override public DeliveryAttempt attempt(long channelId,String eventKey,String title,String relativePath){return delivery.deliverCarePlanAttempt(channelId,eventKey,title,relativePath);}
    }
}
