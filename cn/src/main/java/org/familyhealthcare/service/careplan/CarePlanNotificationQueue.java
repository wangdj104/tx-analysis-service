package org.familyhealthcare.service.careplan;

/**
 * Transactional outbox boundary. Implementations must persist notification jobs on
 * the same datasource and active READ_COMMITTED transaction as the business event.
 * Failure must throw so the aggregate/event/command/outbox transaction rolls back.
 * No transport or after-commit enqueue belongs here.
 */
public interface CarePlanNotificationQueue {
    void enqueue(long eventId);
}
