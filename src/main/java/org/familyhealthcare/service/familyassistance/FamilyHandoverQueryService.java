package org.familyhealthcare.service.familyassistance;

import org.familyhealthcare.service.workflow.HouseholdWorkflowSchema;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService.HandoverPermission;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** 单次有界读取：独立读取已提交事实；权限或同指针修订变化时不返回部分正文或姓名。 */
@Service
public class FamilyHandoverQueryService {
    private final JdbcTemplate jdbc;
    private final FamilyAssistanceProperties properties;
    private final FamilyAssistanceAuthorizationService auth;
    private final FamilyHandoverReadRepository reads;
    private final HouseholdWorkflowSchema schema;
    private final TransactionTemplate transaction;
    public FamilyHandoverQueryService(JdbcTemplate jdbc,FamilyAssistanceProperties properties,FamilyAssistanceAuthorizationService auth,
                                      FamilyHandoverReadRepository reads,HouseholdWorkflowSchema schema,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.properties=properties;this.auth=auth;this.reads=reads;this.schema=schema;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(true);transaction.setTimeout(5);
    }
    public Map<String,Object> preview(long actor,long patient,long careItem) { return read(actor,patient,careItem,false); }
    public Map<String,Object> content(long actor,long patient,long assistance) { return read(actor,patient,assistance,true); }

    private Map<String,Object> read(long actor,long patient,long target,boolean requested) {
        requireId(target);
        return execute(actor,patient,false,()->{
            Map<String,Object> first=requested?reads.byAssistance(patient,target,true):reads.bySource(patient,target,true);
            Map<String,Object> projection=FamilyHandoverProjection.project(first,patient,requested?target:null,properties.isEnabled());
            boolean enrich=requested&&projection.get("currentAssistanceId")!=null;
            Set<Long> subjects=enrich?FamilyHandoverProjection.participants(first,"a"):Collections.emptySet();
            Map<Long,HandoverPermission> initial=enrich?auth.handoverPermissions(patient,subjects):Collections.emptyMap();
            Map<Long,String> names=enrich?names(initial):Collections.emptyMap();
            releaseBody(first);
            Map<String,Object> last=requested?reads.byAssistance(patient,target,false):reads.bySource(patient,target,false);
            Map<Long,HandoverPermission> current=enrich?auth.handoverPermissions(patient,subjects):Collections.emptyMap();
            auth.requireRead(actor,patient,"HANDOVER");
            // 同一个请求也会发生转交、确认或重开；不能只比较 pointer。
            FamilyHandoverProjection.requireSame(first,last,requested);
            if(enrich)FamilyHandoverProjection.enrich(projection,first,"a",current,names,properties.now());
            return FamilyHandoverProjection.bounded(projection,96*1024);
        });
    }
    public Map<String,Object> work(long actor,long patient,String cursor,int limit) {
        FamilyHandoverReadRepository.page(limit);String scope="hw1:"+actor+":"+patient+":HANDOVER_WORK:"+limit;
        long after=position(cursor,scope);
        return execute(actor,patient,false,()->{
            List<Map<String,Object>> found=reads.work(patient,after,limit);boolean more=found.size()>limit;
            // 哨兵只负责判断还有下一页，不进入人员集合，最多验证25行的75位参与者。
            if(more)found=new ArrayList<>(found.subList(0,limit));
            Set<Long> subjects=new TreeSet<>(),ids=new TreeSet<>();List<Map<String,Object>> items=new ArrayList<>();
            for(Map<String,Object> row:found) {
                Map<String,Object> item=FamilyHandoverProjection.project(row,patient,null,properties.isEnabled());
                if(!Boolean.TRUE.equals(item.get("currentSource")))throw FamilyHandoverProjection.unavailable();
                item.remove("note");items.add(item);releaseBody(row);
                subjects.addAll(FamilyHandoverProjection.participants(row,"c"));ids.add((Long)item.get("assistanceId"));
            }
            Map<Long,HandoverPermission> initial=auth.handoverPermissions(patient,subjects);Map<Long,String> names=names(initial);
            List<Map<String,Object>> last=reads.workContexts(patient,ids);
            Map<Long,HandoverPermission> current=auth.handoverPermissions(patient,subjects);
            auth.requireRead(actor,patient,"HANDOVER");
            Map<Long,Map<String,Object>> contexts=new HashMap<>();
            for(Map<String,Object> row:last)contexts.put(number(row.get("c_id")),row);
            if(contexts.size()!=found.size())throw FamilyHandoverProjection.changed();
            for(int i=0;i<found.size();i++) {
                Map<String,Object> row=found.get(i);FamilyHandoverProjection.requireSame(row,contexts.get(number(row.get("c_id"))),false);
                FamilyHandoverProjection.enrich(items.get(i),row,"c",current,names,properties.now());
            }
            String next=more?encode(scope,(Long)items.get(items.size()-1).get("assistanceId")):null;
            return FamilyHandoverProjection.bounded(WorkflowData.map("patientId",patient,"items",items,"more",more,"nextCursor",next),128*1024);
        });
    }
    public Map<String,Object> candidates(long actor,long patient,String operation,Long careItemId,Long assistanceId,String search) {
        if(!Arrays.asList("CREATE","ASSIGN","HANDOFF").contains(operation))throw FamilyHandoverProjection.invalidRequest();
        boolean requested=!"CREATE".equals(operation);
        if(requested?(assistanceId==null||careItemId!=null):(careItemId==null||assistanceId!=null))throw FamilyHandoverProjection.invalidRequest();
        long target=requested?assistanceId:careItemId;requireId(target);String name=FamilyHandoverProjection.search(search);
        return execute(actor,patient,true,()->{
            Map<String,Object> first=reads.candidateContext(patient,target,requested,true);
            Map<String,Object> context=FamilyHandoverProjection.context(first,patient,requested?target:null,properties.isEnabled());
            requireOperation(actor,operation,first,context,requested);
            Set<Long> subjects=reads.candidateIds(patient,number(first.get("owner_id")),name);
            Map<Long,HandoverPermission> initial=auth.handoverPermissions(patient,subjects);Map<Long,String> names=names(initial);
            Map<String,Object> last=reads.candidateContext(patient,target,requested,false);
            Map<Long,HandoverPermission> current=auth.handoverPermissions(patient,subjects);
            auth.requireWrite(actor,patient,"HANDOVER");
            FamilyHandoverProjection.requireSame(first,last,requested);
            requireOperation(actor,operation,last,context,requested);
            List<Map<String,Object>> items=new ArrayList<>();
            for(Long subject:subjects)if(FamilyHandoverProjection.canWrite(initial,subject)&&FamilyHandoverProjection.canWrite(current,subject))
                items.add(FamilyHandoverProjection.participant(subject,current,names));
            Map<String,Object> result=new LinkedHashMap<>();
            for(String key:Arrays.asList("patientId","sourceKind","careItemId","requestKind","familyWorkflowVersion","assistanceId","version","cycle","state","currentAssistanceId","currentSource"))
                result.put(key,context.get(key));
            result.put("operation",operation);result.put("items",items);
            return FamilyHandoverProjection.bounded(result,96*1024);
        });
    }
    private void requireOperation(long actor,String operation,Map<String,Object> row,Map<String,Object> context,boolean requested) {
        if("UNAVAILABLE".equals(context.get("contentStatus")))throw FamilyHandoverProjection.unavailable();
        String prefix=requested?"a":"c";Object state=row.get(prefix+"_state");
        boolean terminal=Arrays.asList("COMPLETED","CANCELLED","CLOSED_UNRESOLVED").contains(state);
        if(!requested) {
            if(!properties.isEnabled())throw FamilyHandoverProjection.featureDisabled();
            if(context.get("currentAssistanceId")!=null&&!terminal)throw FamilyHandoverProjection.actionConflict("SOURCE_ALREADY_TRACKED");
            return;
        }
        if(!Boolean.TRUE.equals(context.get("currentSource")))throw FamilyHandoverProjection.actionConflict("SOURCE_SUPERSEDED");
        if(terminal)throw FamilyHandoverProjection.actionConflict("REQUEST_ENDED");
        boolean owner=Objects.equals(number(row.get("owner_id")),actor),origin=Objects.equals(number(row.get(prefix+"_originator_id")),actor);
        if("ASSIGN".equals(operation)) {
            if(!owner&&!origin)throw FamilyHandoverProjection.accessDenied();
            if(!"REQUESTED".equals(state))throw FamilyHandoverProjection.actionConflict("FRESH_HANDOFF_REQUIRED");
        } else if(!owner&&!origin&&!Objects.equals(number(row.get(prefix+"_accepted_by")),actor))throw FamilyHandoverProjection.accessDenied();
    }
    private Map<Long,String> names(Map<Long,HandoverPermission> permissions) {
        Set<Long> eligible=new TreeSet<>();permissions.forEach((id,p)->{if(p.canWrite())eligible.add(id);});
        return reads.names(eligible);
    }
    private Map<String,Object> execute(long actor,long patient,boolean write,Supplier<Map<String,Object>> body) {
        requireId(actor);requireId(patient);
        try {
            return transaction.execute(status->{
                requireFreshTransaction();
                if(write)auth.requireWrite(actor,patient,"HANDOVER");else auth.requireRead(actor,patient,"HANDOVER");
                schema.inspectStorageMetadata().requireStructurallyReady();
                return body.get();
            });
        } catch(WorkflowException failure) {
            // 新入口只使用固定安全文案；不把正文、姓名、搜索词或底层SQL带进错误。
            if(failure.getStatus()==403)throw FamilyHandoverProjection.accessDenied();
            if("SCHEMA_NOT_READY".equals(failure.getErrorCode()))throw FamilyHandoverProjection.storageNotReady();
            throw failure;
        } catch(DataAccessException|TransactionException failure) {
            throw FamilyHandoverProjection.unavailable();
        }
    }
    private void requireFreshTransaction() {
        DataSource source=Objects.requireNonNull(jdbc.getDataSource());Connection connection=DataSourceUtils.getConnection(source);
        try {
            // REQUIRES_NEW 挂起外层 RR；仍核对实际连接，避免只凭注解宣称已看到最新提交。
            if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||!DataSourceUtils.isConnectionTransactional(connection,source)||connection.getAutoCommit()
                    ||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw FamilyHandoverProjection.unavailable();
        } catch(SQLException failure){throw FamilyHandoverProjection.unavailable();}
        finally{DataSourceUtils.releaseConnection(connection,source);}
    }
    private static void releaseBody(Map<String,Object> row){row.remove("s_data_json");row.remove("s_title");row.remove("a_evidence_json");row.remove("c_evidence_json");}
    private static Long number(Object value){return value instanceof Number?((Number)value).longValue():null;}
    private static void requireId(long id){if(id<1||id>9007199254740991L)throw FamilyHandoverProjection.invalidRequest();}
    private static String encode(String scope,long id){return Base64.getUrlEncoder().withoutPadding().encodeToString((scope+":"+id).getBytes(StandardCharsets.US_ASCII));}
    private static long position(String cursor,String scope) {
        if(cursor==null)return 0;
        if(cursor.isEmpty()||cursor.length()>180||!cursor.matches("[A-Za-z0-9_-]+"))throw FamilyHandoverProjection.invalidCursor();
        try {
            String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII);
            if(!decoded.startsWith(scope+":"))throw FamilyHandoverProjection.invalidCursor();
            String value=decoded.substring(scope.length()+1);if(!value.matches("[1-9][0-9]{0,15}"))throw FamilyHandoverProjection.invalidCursor();
            long id=Long.parseLong(value);if(id>9007199254740991L||!encode(scope,id).equals(cursor))throw FamilyHandoverProjection.invalidCursor();return id;
        } catch(IllegalArgumentException failure){throw FamilyHandoverProjection.invalidCursor();}
    }
}
