package org.familyhealthcare.service.stockworkflow;
import org.familyhealthcare.service.medicationexecution.*;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service public class StockWorkflowAuthorizationService  {
    private final MedicationExecutionAuthorizationService medication;
    public StockWorkflowAuthorizationService(JdbcTemplate jdbc)  {
        medication=new MedicationExecutionAuthorizationService(jdbc,new MedicationExecutionProperties());
    }
    public void requireRead(long actor,long patient)  {
        medication.requireRead(actor,patient);
    }
    public void requireWrite(long actor,long patient)  {
        medication.requireWrite(actor,patient);
    }
    public void requireNotificationAdmin(long actor)  {
        medication.requireNotificationAdmin(actor);
    }
    public long owner(long patient)  {
        return medication.owner(patient);
    }
    public boolean canRead(long actor,long patient)  {
        return medication.canRead(actor,patient);
    }
    public boolean canWrite(long actor,long patient)  {
        return medication.canWrite(actor,patient);
    }
    public String relation(long actor,long patient)  {
        return medication.relation(actor,patient);
    }
    public static WorkflowException denied()  {
        return MedicationExecutionAuthorizationService.denied();
    }
}
