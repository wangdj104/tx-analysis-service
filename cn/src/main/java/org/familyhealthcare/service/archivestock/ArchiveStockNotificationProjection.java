package org.familyhealthcare.service.archivestock;

import org.familyhealthcare.service.stockworkflow.StockWorkflowAuthorizationService;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** List-only policy projection. No current-facts, ORDER, daily-use, receipt or per-row queries. */
public final class ArchiveStockNotificationProjection {
    private final JdbcTemplate jdbc;
    private final StockWorkflowAuthorizationService auth;
    public ArchiveStockNotificationProjection(JdbcTemplate jdbc){this.jdbc=jdbc;auth=new StockWorkflowAuthorizationService(jdbc);}
    public void append(long actor,long patient,List<Map<String,Object>> rows,String idKey){
        if(rows.isEmpty())return;
        if(!auth.canRead(actor,patient)){unavailable(rows,"UNAVAILABLE");return;}
        ArchiveStockNotificationSchema.Status status=null;
        Map<Long,ArchiveStockNotificationRepository.State> states=Collections.emptyMap();
        try{
            status=new ArchiveStockNotificationSchema().inspect(jdbc);
            if(status==ArchiveStockNotificationSchema.Status.READY)states=new ArchiveStockNotificationRepository(jdbc).states(patient);
        }catch(RuntimeException unavailable){
            // Only this nontransactional read projection may preserve native facts after a sidecar failure.
            // Mutation callers must see the original failure so their transaction cannot report success.
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw unavailable;
            status=null;states=Collections.emptyMap();
        }
        if(!auth.canRead(actor,patient)){unavailable(rows,"UNAVAILABLE");return;}
        if(status!=ArchiveStockNotificationSchema.Status.READY){unavailable(rows,status==null?"UNAVAILABLE":status.name());return;}
        boolean write=auth.canWrite(actor,patient);
        if(!auth.canRead(actor,patient)){unavailable(rows,"UNAVAILABLE");return;}
        for(Map<String,Object> row:rows){
            long stock=ArchiveStockNotificationContracts.id(row.get(idKey));
            ArchiveStockNotificationRepository.State state=states.get(stock);
            Map<String,Object> policy=state==null?WorkflowData.map("stockId",stock,"marked",false):state.asMap();
            policy.put("schemaStatus","READY");policy.put("canWrite",write);row.put("reminderPolicy",policy);
        }
    }
    private static void unavailable(List<Map<String,Object>> rows,String status){
        for(Map<String,Object> row:rows)row.put("reminderPolicy",WorkflowData.map("schemaStatus",status,"canWrite",false));
    }
}
