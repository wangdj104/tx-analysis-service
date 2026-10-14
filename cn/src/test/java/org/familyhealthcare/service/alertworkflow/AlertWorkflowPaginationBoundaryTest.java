package org.familyhealthcare.service.alertworkflow;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
class AlertWorkflowPaginationBoundaryTest {
    AlertWorkflowHarness h;AlertWorkflowQueryService q;int reads;
    @BeforeEach void setup() throws Exception {
        h=new AlertWorkflowHarness();h.adopt();
        Map<String,Object>values=map("patientId",10L,"ownerUserId",1L,"sourceType","HEALTH_MEASUREMENT","sourceId",101L,"sourceRevision",1L,"metric","GLUCOSE","classification",Collections.emptyMap(),"workflowMode","PERSONAL","responsibleUserId",1L,"dueAt",null);
        for(int i=1;i<205;i++)h.db.tx.execute(tx->h.repo.createAlert(values));
        AlertWorkflowRepository counted=new AlertWorkflowRepository(h.db.jdbc,h.p,h.auth){@Override public Map<String,Object>readAlert(long actor,long id){reads++;return super.readAlert(actor,id);}};
        q=new AlertWorkflowQueryService(h.db.jdbc,counted,h.auth,h.p,new AlertWorkflowEvidenceService(h.db.jdbc,counted,h.auth,h.p));
    }
    @Test void fixedStateFiltersRejectClosedBacklogBeforeAnyProjection() {
        h.db.jdbc.update("UPDATE alert_record SET workflow_state='CLOSED'");Map<String,Object>page=q.list(1,10,"WAITING_REVIEW",null,1);
        assertEquals(0,reads);assertEquals(Collections.emptyList(),page.get("items"));assertNull(page.get("nextCursor"));
    }
    @Test void computedQueueHasBoundedEmptyPagesAndFairBoundaryContinuation() {
        h.db.jdbc.update("UPDATE alert_record SET responsible_user_id=1");h.db.jdbc.update("UPDATE alert_record SET responsible_user_id=NULL WHERE id>=200");
        Map<String,Object>page=q.list(1,10,"BLOCKED",null,1);assertEquals(Collections.emptyList(),page.get("items"));assertNotNull(page.get("nextCursor"));assertTrue(reads<=100,"One filtered request may project at most 100 candidates");
        List<Long>seen=new ArrayList<>();String cursor=(String)page.get("nextCursor");int pages=0;
        while(cursor!=null){assertTrue(++pages<10);int before=reads;page=q.list(1,10,"BLOCKED",cursor,1);assertTrue(reads-before<=100);for(Object item:(List<?>)page.get("items"))seen.add(id(((Map<?,?>)item).get("alertId")));cursor=(String)page.get("nextCursor");}
        assertEquals(Arrays.asList(200L,201L,202L,203L,204L,205L),seen);
    }
}
