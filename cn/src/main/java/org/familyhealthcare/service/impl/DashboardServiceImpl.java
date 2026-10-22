package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.entity.DryWeightMonthly;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.entity.MedicationLog;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.MedicalRecordItemMapper;
import org.familyhealthcare.mapper.MedicalRecordMapper;
import org.familyhealthcare.mapper.MedicationLogMapper;
import org.familyhealthcare.service.DashboardService;
import org.familyhealthcare.service.DialysisRecordService;
import org.familyhealthcare.service.DryWeightMonthlyService;
import org.familyhealthcare.service.MedicalRecordService;
import org.familyhealthcare.service.MedicationService;
import org.familyhealthcare.service.PatientService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.DashboardSummaryVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.SnapshotQualification;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema;
import org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader;
import org.familyhealthcare.service.medicalreview.MedicalReviewSchema;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.ibatis.session.SqlSessionFactory;
import java.sql.Connection;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

import static org.familyhealthcare.util.ExportLocalization.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DashboardServiceImpl implements DashboardService {
    @Autowired private MedicalRecordValidityProjection medicalValidity;
    @Autowired private JdbcTemplate medicalJdbc;
    @Autowired private DataSourceTransactionManager medicalTransactions;
    @Autowired private SqlSessionFactory medicalSqlSessions;
    @Autowired private MedicalRecordReviewAccess medicalAccess;
    @Autowired private MedicalReviewQualificationReader medicalQualification;


    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private PatientService patientService;

    @Autowired
    private DialysisRecordService dialysisRecordService;

    @Autowired
    private MedicalRecordService medicalRecordService;

    @Autowired
    private MedicalRecordMapper medicalRecordMapper;

    @Autowired
    private MedicalRecordItemMapper medicalRecordItemMapper;

    @Autowired
    private MedicationLogMapper medicationLogMapper;

    @Autowired
    private DryWeightMonthlyService dryWeightMonthlyService;

    @Override
    public DashboardSummaryVO getSummary(Long patientId) {
        Long userId = dataScopeHelper.requireUserId();
        if(!safeMedicalId(userId))throw new IllegalStateException("Access denied to source record.");
        requireMedicalActor(userId);requireMedicalUnowned();
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        DashboardSummaryVO vo = new DashboardSummaryVO();
        MedicalSnapshot medical=null;boolean released=false;
        try {

        List<Patient> patients = patientService.list();
        if (patientId != null) {
            vo.setPatientCount(1L);
        } else {
            vo.setPatientCount((long) patients.stream().filter(p -> p.getStatus() != null && p.getStatus() == 1).count());
        }

        QueryWrapper<DialysisRecord> dialysisQw = new QueryWrapper<DialysisRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(dialysisQw);
        }
        if (patientId != null) {
            dialysisQw.eq("patient_id", patientId);
        }
        dialysisQw.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
        vo.setDialysisCountMonth(dialysisRecordService.count(dialysisQw));

        QueryWrapper<MedicalRecord> medicalQw = new QueryWrapper<MedicalRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(medicalQw);
        }
        if (patientId != null) {
            medicalQw.eq("patient_id", patientId);
        }
        medicalQw.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
        vo.setMedicalRecordCountMonth(medicalRecordService.count(medicalQw));

        QueryWrapper<MedicationLog> medLogQw = new QueryWrapper<MedicationLog>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(medLogQw);
        }
        if (patientId != null) {
            medLogQw.eq("patient_id", patientId);
        }
        medLogQw.apply("DATE_FORMAT(administration_time, '%Y-%m') = {0}", month);
        vo.setMedicationLogCountMonth(medicationLogMapper.selectCount(medLogQw));

        medical=readMedicalSnapshot(patientId,userId);
        vo.setAbnormalItemCount(medical.count);
        vo.setRecentAbnormalItems(medical.abnormal);
        vo.setMedicalQualificationStatus(medical.available()?"AVAILABLE":"UNAVAILABLE");
        vo.setMedicalQualificationAsOf(medical.checkedAt);
        vo.setMedicalQualificationSemantics(medical.available()?MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT.name():null);

        List<DryWeightMonthly> dryWeights = patientId != null
                ? dryWeightMonthlyService.listAllOrderByMonth(patientId)
                : java.util.Collections.emptyList();
        if (!dryWeights.isEmpty()) {
            DryWeightMonthly latest = dryWeights.get(0);
            vo.setCurrentDryWeight(latest.getDryWeight());
            vo.setCurrentDryWeightMonth(latest.getYearMonth());
            if (dryWeights.size() > 1) {
                DryWeightMonthly prev = dryWeights.get(1);
                vo.setPreviousDryWeight(prev.getDryWeight());
                if (latest.getDryWeight() != null && prev.getDryWeight() != null) {
                    vo.setDryWeightDelta(latest.getDryWeight().subtract(prev.getDryWeight()));
                }
            }
        }

        QueryWrapper<DialysisRecord> recentDialysisQw = new QueryWrapper<DialysisRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(recentDialysisQw);
        }
        if (patientId != null) {
            recentDialysisQw.eq("patient_id", patientId);
        }
        recentDialysisQw.orderByDesc("record_date").last("limit 5");
        List<DialysisRecord> recentDialysis = dialysisRecordService.list(recentDialysisQw);
        vo.setRecentDialysis(recentDialysis);

        vo.setRecentMedicalRecords(medical.recent);

        vo.setMonthlyTrend(buildMonthlyTrend(userId, patientId));
        Map<String, Long> dehydrationStats = buildDehydrationStats(userId, month, patientId);
        vo.setDehydrationStats(dehydrationStats);
        vo.setDryWeightTrend(buildDryWeightTrend(dryWeights));
        fillHealthInsights(vo, recentDialysis, dehydrationStats, userId, month, patientId);

        requireMedicalRelease(medical,userId);
        released=true;return vo;
        } finally {
            if(!released) {
                if(medical!=null)clearMedicalRecords(medical.recent);
                vo.setAbnormalItemCount(null);vo.setRecentAbnormalItems(null);vo.setMedicalQualificationStatus(null);
                vo.setMedicalQualificationAsOf(null);vo.setMedicalQualificationSemantics(null);vo.setHealthRiskLevel(null);vo.setHealthRiskLabel(null);
            }
        }
    }

    /** Own qualification in one RR frame; current authority is checked independently. */
    private MedicalSnapshot readMedicalSnapshot(Long patientId, long actor) {
        requireMedicalUnowned();requireMedicalActor(actor);
        List<MedicalRecord> recent=new ArrayList<>();
        RuntimeException[] diagnosed=new RuntimeException[1];
        boolean[] committed={false};
        MedicalSnapshot snapshot;
        try {
            TransactionTemplate template=medicalTemplate(Connection.TRANSACTION_REPEATABLE_READ);
            snapshot=template.execute(transaction->{
                try {
                    MedicalResource resource=new MedicalResource(Connection.TRANSACTION_REPEATABLE_READ);
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                        @Override public void afterCompletion(int status){committed[0]=status==STATUS_COMMITTED;if(!committed[0])clearMedicalRecords(recent);}
                    });
                    MedicalRecordValidityProjection.Scope scope;
                    try {scope=medicalValidity.open();}catch(WorkflowException failure){resource.verify();throw failure;}
                    catch(RuntimeException failure){throw medicalUnavailable();}
                    resource.verify();
                    QueryWrapper<MedicalRecord> recordQw=new QueryWrapper<>();
                    if(!CurrentUserUtil.isAdmin())dataScopeHelper.applyUserScope(recordQw);
                    if(patientId!=null)recordQw.eq("patient_id",patientId);
                    recordQw.select("id","patient_id","user_id","content_version","verification_status","record_date","patient_name");
                    List<MedicalRecord> candidates;
                    try {resource.verify();candidates=medicalRecordService.list(recordQw);resource.verify();}
                    catch(WorkflowException failure){throw failure;}catch(RuntimeException failure){throw medicalUnavailable();}
                    if(candidates==null)throw medicalChanged();
                    Map<Long,MedicalHeader> headers=new LinkedHashMap<>();
                    for(MedicalRecord candidate:candidates) {
                        MedicalHeader header=new MedicalHeader(candidate),prior=headers.putIfAbsent(header.id,header);
                        if(prior!=null&&!prior.sameSnapshot(header))throw medicalChanged();
                    }
                    requireMedicalAdmission(medicalHeaders(headers),actor);
                    requireMedicalActor(actor);resource.verify();

                    QueryWrapper<MedicalRecord> recentQw=new QueryWrapper<>();
                    if(!CurrentUserUtil.isAdmin())dataScopeHelper.applyUserScope(recentQw);
                    if(patientId!=null)recentQw.eq("patient_id",patientId);
                    recentQw.apply(scope.activeSql("medical_record."));
                    recentQw.orderByDesc("record_date").last("limit 5");
                    List<MedicalRecord> selected;
                    try {resource.verify();selected=medicalRecordMapper.selectList(recentQw);resource.verify();}
                    catch(WorkflowException failure){throw failure;}catch(RuntimeException failure){throw medicalUnavailable();}
                    if(selected==null)throw medicalChanged();
                    recent.addAll(selected);clearMedicalRecords(recent);
                    for(MedicalRecord record:recent) {
                        MedicalHeader header=new MedicalHeader(record),original=headers.get(header.id);
                        if(original==null||!original.sameSnapshot(header))throw medicalChanged();
                    }
                    // Preserve the old parent9/native display projection for a metadata-unavailable response.
                    try {scope.project(recent);resource.verify();}
                    catch(WorkflowException failure){resource.verify();throw failure;}
                    catch(RuntimeException failure){throw medicalUnavailable();}

                    List<Long> ids=new ArrayList<>(headers.keySet());
                    Map<Long,State> states=new LinkedHashMap<>();
                    SnapshotQualification previous=null;
                    for(int offset=0;offset<Math.max(1,ids.size());offset+=100) {
                        requireMedicalActor(actor);resource.verify();
                        List<Long> part=ids.subList(offset,Math.min(offset+100,ids.size()));
                        SnapshotQualification batch;
                        try {batch=scope.qualifiedSnapshotResult(part);}
                        catch(MedicalReviewQualificationReader.MetadataUnavailable unavailable) {
                            // No code/string/cause inference: this exact reader classification alone may be neutral.
                            resource.verify();requireMedicalActor(actor);clearMedicalRecords(recent);
                            return new MedicalSnapshot(headers,Collections.emptyMap(),recent,null,null,null);
                        } catch(WorkflowException failure){resource.verify();throw failure;}
                        catch(RuntimeException failure){throw medicalUnavailable();}
                        resource.verify();requireMedicalActor(actor);
                        if(batch==null||batch.checkedAt==null||batch.semantics!=MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT
                                ||batch.parent9Storage!=scope.storage
                                ||batch.reviewStorage!=MedicalReviewSchema.Status.READY&&batch.reviewStorage!=MedicalReviewSchema.Status.ABSENT
                                ||batch.parent9Storage!=MedicalRecordCorrectionSchema.Status.READY&&batch.parent9Storage!=MedicalRecordCorrectionSchema.Status.ABSENT
                                ||previous!=null&&(previous.reviewStorage!=batch.reviewStorage||previous.parent9Storage!=batch.parent9Storage)
                                ||!batch.states.keySet().equals(new LinkedHashSet<>(part)))throw medicalChanged();
                        for(Long id:part) {
                            State state=batch.states.get(id);MedicalHeader header=headers.get(id);
                            if(state==null||!state.reviewObserved||state.signature==null||state.signature.isEmpty()||state.sourceReleaseSignature==null
                                    ||state.checkedAt==null||!state.checkedAt.equals(batch.checkedAt)||state.qualificationSemantics!=batch.semantics
                                    ||state.id!=id||!safeMedicalId(state.version)||!Objects.equals(header.patient,state.patientId)
                                    ||!Objects.equals(header.owner,state.ownerId)||!Objects.equals(header.version,state.version)
                                    ||!Objects.equals(header.review,state.review))throw medicalChanged();
                            states.put(id,state);
                        }
                        previous=batch;
                    }
                    List<Long> qualified=new ArrayList<>();
                    for(Map.Entry<Long,State> entry:states.entrySet())if(entry.getValue().eligible())qualified.add(entry.getKey());
                    long count=0;List<MedicalRecordItem> newest=new ArrayList<>();
                    for(int offset=0;offset<qualified.size();offset+=100) {
                        List<Long> part=qualified.subList(offset,Math.min(offset+100,qualified.size()));
                        String marks=medicalMarks(part.size());
                        long chunk=medicalQuery(resource,"SELECT COUNT(*) FROM medical_record_item WHERE record_id IN ("+marks+") AND is_abnormal IN (-1,1)",part,rows->{
                            if(!rows.next())throw medicalChanged();Object value=rows.getObject(1);
                            if(!(value instanceof Number))throw medicalChanged();
                            long exact;
                            try {exact=new BigDecimal(value.toString()).longValueExact();}catch(ArithmeticException|NumberFormatException invalid){throw medicalChanged();}
                            if(exact<0||rows.next())throw medicalChanged();return exact;
                        });
                        try {count=Math.addExact(count,chunk);}catch(ArithmeticException overflow){throw medicalChanged();}
                        List<MedicalRecordItem> items=medicalQuery(resource,
                                "SELECT id,record_id,item_name,result_value,unit,is_abnormal,created_at FROM medical_record_item WHERE record_id IN ("+marks+") AND is_abnormal IN (-1,1) ORDER BY created_at DESC LIMIT 20",part,rows->{
                            List<MedicalRecordItem> result=new ArrayList<>();
                            while(rows.next()) {
                                Long recordId=medicalNumber(rows,"record_id");
                                if(recordId==null||!part.contains(recordId)||result.size()>=20)throw medicalChanged();
                                MedicalRecordItem item=new MedicalRecordItem();item.setId(medicalNumber(rows,"id"));item.setRecordId(recordId);
                                item.setItemName(rows.getString("item_name"));item.setResultValue(rows.getString("result_value"));item.setUnit(rows.getString("unit"));
                                Long flag=medicalNumber(rows,"is_abnormal");if(flag==null||flag!=-1&&flag!=1)throw medicalChanged();item.setIsAbnormal(flag.intValue());
                                java.sql.Timestamp time=rows.getTimestamp("created_at");item.setCreatedAt(time==null?null:time.toLocalDateTime());result.add(item);
                            }return result;
                        });
                        newest.addAll(items);
                        newest.sort(java.util.Comparator.comparing(MedicalRecordItem::getCreatedAt,java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
                        if(newest.size()>20)newest.subList(20,newest.size()).clear();
                    }
                    List<Map<String,Object>> abnormal=new ArrayList<>();
                    for(MedicalRecordItem item:newest.subList(0,Math.min(8,newest.size()))) {
                        MedicalHeader header=headers.get(item.getRecordId());Map<String,Object> row=new HashMap<>();
                        row.put("recordDate",header.recordDate);row.put("patientName",header.patientName);row.put("itemName",item.getItemName());
                        row.put("resultValue",item.getResultValue());row.put("unit",item.getUnit());row.put("isAbnormal",item.getIsAbnormal());abnormal.add(row);
                    }
                    for(MedicalRecord record:recent) {
                        State state=states.get(record.getId());record.setManaged(state.managed);record.setValidity(state.validity);record.setSourceStatus(state.status());
                        MedicalRecordValidityProjection.projectReviewSummary(record,state);
                    }
                    requireMedicalActor(actor);resource.verify();
                    return new MedicalSnapshot(headers,states,recent,count,abnormal,previous.checkedAt.toString());
                } catch(WorkflowException|IllegalStateException failure){diagnosed[0]=failure;throw failure;}
                catch(RuntimeException failure){WorkflowException safe=medicalUnavailable();diagnosed[0]=safe;throw safe;}
            });
        } catch(RuntimeException failure) {
            clearMedicalRecords(recent);requireMedicalUnowned();
            // Only the exact callback diagnosis is trusted; completion failures have no public cause or payload.
            if(failure==diagnosed[0])throw failure;
            throw medicalUnavailable();
        } catch(Error failure){clearMedicalRecords(recent);throw failure;}
        try {
            requireMedicalUnowned();requireMedicalActor(actor);
            if(snapshot==null||!committed[0])throw medicalUnavailable();return snapshot;
        } catch(RuntimeException|Error failure){clearMedicalRecords(recent);throw failure;}
    }

    /** Refresh only current scalar headers, close that owner, then use the existing real authority predicate. */
    private void requireMedicalRelease(MedicalSnapshot snapshot,long actor) {
        requireMedicalUnowned();requireMedicalActor(actor);
        RuntimeException[] diagnosed=new RuntimeException[1];boolean[] committed={false};
        List<MedicalRecord> current;
        try {
            current=medicalTemplate(Connection.TRANSACTION_READ_COMMITTED).execute(transaction->{
                try {
                    MedicalResource resource=new MedicalResource(Connection.TRANSACTION_READ_COMMITTED);
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                        @Override public void afterCompletion(int status){committed[0]=status==STATUS_COMMITTED;}
                    });
                    List<MedicalRecord> refreshed=new ArrayList<>();List<Long> ids=new ArrayList<>(snapshot.headers.keySet());
                    for(int offset=0;offset<ids.size();offset+=100) {
                        List<Long> part=ids.subList(offset,Math.min(offset+100,ids.size()));
                        List<MedicalRecord> batch=medicalQuery(resource,
                                "SELECT id,patient_id,user_id,content_version,verification_status FROM medical_record WHERE id IN ("+medicalMarks(part.size())+") ORDER BY id LIMIT "+(part.size()+1),part,rows->{
                            Set<Long> seen=new LinkedHashSet<>();List<MedicalRecord> result=new ArrayList<>();
                            while(rows.next()) {
                                MedicalRecord record=new MedicalRecord();record.setId(medicalNumber(rows,"id"));record.setPatientId(medicalNumber(rows,"patient_id"));
                                record.setUserId(medicalNumber(rows,"user_id"));record.setContentVersion(medicalNumber(rows,"content_version"));record.setVerificationStatus(rows.getString("verification_status"));
                                MedicalHeader header=new MedicalHeader(record),original=snapshot.headers.get(header.id);
                                if(!part.contains(header.id)||!seen.add(header.id)||original==null||!original.sameBinding(header))throw medicalChanged();result.add(record);
                            }
                            if(!seen.equals(new LinkedHashSet<>(part)))throw medicalChanged();return result;
                        });
                        refreshed.addAll(batch);
                    }
                    requireMedicalActor(actor);resource.verify();return refreshed;
                }catch(WorkflowException|IllegalStateException failure){diagnosed[0]=failure;throw failure;}
                catch(RuntimeException failure){WorkflowException safe=medicalUnavailable();diagnosed[0]=safe;throw safe;}
            });
        }catch(RuntimeException failure) {
            requireMedicalUnowned();
            if(failure==diagnosed[0])throw failure;
            throw medicalUnavailable();
        }
        requireMedicalUnowned();requireMedicalActor(actor);
        if(current==null||!committed[0])throw medicalUnavailable();
        // Admission itself owns RC. Invoking it inside the header-refresh RC transaction is forbidden.
        requireMedicalAdmission(current,actor);
        requireMedicalUnowned();requireMedicalActor(actor);
    }

    private TransactionTemplate medicalTemplate(int isolation) {
        requireMedicalUnowned();
        TransactionTemplate template=new TransactionTemplate(medicalTransactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);template.setReadOnly(true);template.setIsolationLevel(isolation);return template;
    }
    private void requireMedicalAdmission(List<MedicalRecord> records,long actor) {
        requireMedicalActor(actor);
        try {medicalAccess.requireReadAdmission(records,actor);}
        catch(WorkflowException|IllegalStateException failure){throw failure;}
        catch(RuntimeException failure){throw medicalUnavailable();}
        requireMedicalActor(actor);
    }
    private void medicalGraph() {
        try {
            if(medicalJdbc==null||medicalJdbc.getDataSource()==null||medicalTransactions==null||medicalTransactions.getDataSource()!=medicalJdbc.getDataSource()
                    ||medicalValidity==null||medicalAccess==null||medicalQualification==null||medicalSqlSessions==null
                    ||medicalSqlSessions.getConfiguration().getEnvironment().getDataSource()!=medicalJdbc.getDataSource()
                    ||!(medicalSqlSessions.getConfiguration().getEnvironment().getTransactionFactory() instanceof org.mybatis.spring.transaction.SpringManagedTransactionFactory))throw medicalUnavailable();
            medicalValidity.requireDataSource(medicalJdbc.getDataSource());medicalQualification.requireDataSource(medicalJdbc.getDataSource());medicalAccess.requireDataSource(medicalJdbc.getDataSource());
        }catch(RuntimeException failure){throw medicalUnavailable();}
    }
    private void requireMedicalUnowned() {
        medicalGraph();
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(medicalJdbc.getDataSource())||TransactionSynchronizationManager.hasResource(medicalSqlSessions))throw medicalUnavailable();
    }
    private static void requireMedicalActor(long actor) {
        if(!safeMedicalId(actor)||!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId()))throw new IllegalStateException("Access denied to source record.");
    }
    private final class MedicalResource {
        final Thread thread=Thread.currentThread();final int isolation;
        final ConnectionHolder holder;final SimpleConnectionHandle handle;final Connection connection;
        MedicalResource(int isolation) {
            this.isolation=isolation;medicalGraph();Object value=TransactionSynchronizationManager.getResource(medicalJdbc.getDataSource());
            if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                    ||value==null||value.getClass()!=ConnectionHolder.class)throw medicalUnavailable();
            holder=(ConnectionHolder)value;
            if(!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()==null
                    ||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw medicalUnavailable();
            handle=(SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();verify();
        }
        void verify() {
            medicalGraph();
            if(Thread.currentThread()!=thread||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                    ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||TransactionSynchronizationManager.getResource(medicalJdbc.getDataSource())!=holder
                    ||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw medicalUnavailable();
            try {if(connection==null||holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()||connection.getTransactionIsolation()!=isolation)throw medicalUnavailable();}
            catch(java.sql.SQLException|IllegalStateException failure){throw medicalUnavailable();}
        }
    }
    private <T> T medicalQuery(MedicalResource resource,String sql,List<?> args,org.springframework.jdbc.core.ResultSetExtractor<T> extractor) {
        resource.verify();
        try {
            T result=medicalJdbc.query(sql,statement->{
                resource.verify();statement.setQueryTimeout(resource.holder.hasTimeout()?Math.min(5,resource.holder.getTimeToLiveInSeconds()):5);
                for(int index=0;index<args.size();index++)statement.setObject(index+1,args.get(index));
            },extractor);
            resource.verify();return result;
        }catch(WorkflowException failure){resource.verify();throw failure;}
        catch(RuntimeException failure){throw medicalUnavailable();}
    }
    private static String medicalMarks(int size){if(size<1||size>100)throw medicalChanged();return String.join(",",Collections.nCopies(size,"?"));}
    private static Long medicalNumber(java.sql.ResultSet rows,String column)throws java.sql.SQLException {
        java.math.BigDecimal value=rows.getBigDecimal(column);if(value==null)return null;
        try{return value.longValueExact();}catch(ArithmeticException invalid){throw medicalChanged();}
    }
    private static boolean safeMedicalId(Long value){return value!=null&&value>0&&value<=9007199254740991L;}
    private static WorkflowException medicalUnavailable(){return new WorkflowException(503,"CURRENT_RESULT_UNAVAILABLE","The current medical record result could not be verified.");}
    private static WorkflowException medicalChanged(){return new WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","The current medical record result could not be verified.");}
    private static void clearMedicalRecords(List<MedicalRecord> records) {
        for(MedicalRecord record:records)if(record!=null){record.setReviewSummary(null);record.setSourceStatus("SOURCE_UNAVAILABLE");}
    }
    private static List<MedicalRecord> medicalHeaders(Map<Long,MedicalHeader> headers) {
        List<MedicalRecord> records=new ArrayList<>();for(MedicalHeader header:headers.values())records.add(header.record());return records;
    }
    private static final class MedicalHeader {
        final long id;final Long patient,owner,version;final String review,patientName;final LocalDate recordDate;
        MedicalHeader(MedicalRecord record) {
            if(record==null||!safeMedicalId(record.getId())||!safeMedicalId(record.getContentVersion())
                    ||record.getPatientId()!=null&&!safeMedicalId(record.getPatientId())||record.getUserId()!=null&&!safeMedicalId(record.getUserId()))throw medicalChanged();
            id=record.getId();patient=record.getPatientId();owner=record.getUserId();version=record.getContentVersion();review=record.getVerificationStatus();
            patientName=record.getPatientName();recordDate=record.getRecordDate();
        }
        boolean sameBinding(MedicalHeader other){return id==other.id&&Objects.equals(patient,other.patient)&&Objects.equals(owner,other.owner);}
        boolean sameSnapshot(MedicalHeader other){return sameBinding(other)&&Objects.equals(version,other.version)&&Objects.equals(review,other.review)
                &&Objects.equals(patientName,other.patientName)&&Objects.equals(recordDate,other.recordDate);}
        MedicalRecord record(){MedicalRecord record=new MedicalRecord();record.setId(id);record.setPatientId(patient);record.setUserId(owner);record.setContentVersion(version);record.setVerificationStatus(review);return record;}
    }
    private static final class MedicalSnapshot {
        final Map<Long,MedicalHeader> headers;final Map<Long,State> states;final List<MedicalRecord> recent;
        final Long count;final List<Map<String,Object>> abnormal;final String checkedAt;
        MedicalSnapshot(Map<Long,MedicalHeader> headers,Map<Long,State> states,List<MedicalRecord> recent,Long count,List<Map<String,Object>> abnormal,String checkedAt) {
            this.headers=Collections.unmodifiableMap(new LinkedHashMap<>(headers));this.states=Collections.unmodifiableMap(new LinkedHashMap<>(states));
            this.recent=Collections.unmodifiableList(new ArrayList<>(recent));this.count=count;
            this.abnormal=abnormal==null?null:Collections.unmodifiableList(new ArrayList<>(abnormal));this.checkedAt=checkedAt;
        }
        boolean available(){return checkedAt!=null;}
    }

    private void fillHealthInsights(DashboardSummaryVO vo, List<DialysisRecord> recentDialysis,
                                    Map<String, Long> dehydrationStats, Long userId, String month, Long patientId) {
        QueryWrapper<DialysisRecord> qw = new QueryWrapper<>();
        dataScopeHelper.applyUserScope(qw);
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        qw.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
        List<DialysisRecord> records = dialysisRecordService.list(qw);

        List<DialysisRecord> normalRecords = records.stream()
                .filter(r -> !"INCOMPLETE".equals(r.getRecordType()))
                .collect(Collectors.toList());

        long match = dehydrationStats.getOrDefault("match", 0L);
        long tooMuch = dehydrationStats.getOrDefault("tooMuch", 0L);
        long insufficient = dehydrationStats.getOrDefault("insufficient", 0L);
        long totalDehydration = match + tooMuch + insufficient;
        BigDecimal matchRate = totalDehydration > 0
                ? BigDecimal.valueOf(match * 100.0 / totalDehydration).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        long over5 = normalRecords.stream().filter(this::isOver5pct).count();
        long bpAbnormal = records.stream().filter(this::isBpAbnormal).count();
        vo.setDialysisMatchRateMonth(matchRate);
        vo.setAvgWeightGainMonth(avg(normalRecords.stream().map(DialysisRecord::getWeightGain).collect(Collectors.toList())));
        vo.setAvgUfAmountMonth(avg(normalRecords.stream().map(DialysisRecord::getUfAmount).collect(Collectors.toList())));
        vo.setOver5pctCountMonth(over5);
        vo.setBpAbnormalCountMonth(bpAbnormal);
        vo.setLatestDialysisStatus(recentDialysis == null || recentDialysis.isEmpty()
                ? text("No dialysis records", "暂无透析记录")
                : dehydrationText(recentDialysis.get(0).getDehydrationStatus()));

        List<String> highlights = new ArrayList<>();
        if (totalDehydration > 0) {
            highlights.add(text("Ultrafiltration on-target rate this month: " + matchRate + "%", "本月超滤达标率：" + matchRate + "%"));
        }
        if (over5 > 0) {
            highlights.add(text("Weight gain exceeded 5% of dry weight " + over5 + " times this month", "本月 " + over5 + " 次增重超过干体重的 5%"));
        }
        if (bpAbnormal > 0) {
            highlights.add(text("Blood pressure was outside the target range " + bpAbnormal + " times this month", "本月 " + bpAbnormal + " 次血压超出目标范围"));
        }
        if (vo.getAbnormalItemCount() != null && vo.getAbnormalItemCount() > 0) {
            highlights.add(text(vo.getAbnormalItemCount() + " abnormal test results need attention", "累计 " + vo.getAbnormalItemCount() + " 项检查结果异常需要关注"));
        }
        if ("UNAVAILABLE".equals(vo.getMedicalQualificationStatus())) {
            highlights.add(text("Medical input is unavailable; the overall assessment is incomplete.", "医疗输入不可用，整体评估不完整。"));
            vo.setHealthHighlights(highlights);vo.setHealthRiskLevel(null);
            vo.setHealthRiskLabel(text("Assessment incomplete: medical qualification unavailable", "评估不完整：医疗来源核对信息不可用"));
            return;
        }
        if (highlights.isEmpty()) {
            highlights.add(text("Recent core indicators are stable. Keep recording regularly.", "近期核心指标较平稳，请继续规律记录。"));
        }
        vo.setHealthHighlights(highlights);

        int riskScore = 0;
        if (matchRate.compareTo(new BigDecimal("60")) < 0 && totalDehydration > 0) {
            riskScore += 2;
        } else if (matchRate.compareTo(new BigDecimal("80")) < 0 && totalDehydration > 0) {
            riskScore += 1;
        }
        if (over5 >= 2) riskScore += 2;
        else if (over5 == 1) riskScore += 1;
        if (bpAbnormal >= 3) riskScore += 2;
        else if (bpAbnormal > 0) riskScore += 1;
        if (vo.getAbnormalItemCount() != null && vo.getAbnormalItemCount() >= 10) riskScore += 2;
        else if (vo.getAbnormalItemCount() != null && vo.getAbnormalItemCount() >= 5) riskScore += 1;

        if (riskScore >= 4) {
            vo.setHealthRiskLevel("HIGH");
            vo.setHealthRiskLabel(text("Needs close attention", "需要重点关注"));
        } else if (riskScore >= 2) {
            vo.setHealthRiskLevel("MEDIUM");
            vo.setHealthRiskLabel(text("Some variation", "存在波动"));
        } else {
            vo.setHealthRiskLevel("LOW");
            vo.setHealthRiskLabel(text("Stable overall", "整体平稳"));
        }
    }

    private BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> nonNull = values.stream().filter(v -> v != null).collect(Collectors.toList());
        if (nonNull.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = nonNull.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(nonNull.size()), 2, RoundingMode.HALF_UP);
    }

    private boolean isOver5pct(DialysisRecord r) {
        if (r == null || r.getWeightGain() == null || r.getWeight5pct() == null) {
            return false;
        }
        return r.getWeightGain().compareTo(r.getWeight5pct()) > 0;
    }

    private boolean isBpAbnormal(DialysisRecord r) {
        if (r == null) {
            return false;
        }
        Integer sys = r.getSystolicBp();
        Integer dia = r.getDiastolicBp();
        return (sys != null && (sys > 140 || sys < 120))
                || (dia != null && (dia > 90 || dia < 70));
    }

    private String dehydrationText(String status) {
        if ("TOO_MUCH".equals(status)) return text("Excessive ultrafiltration", "超滤过量");
        if ("INSUFFICIENT".equals(status)) return text("Insufficient ultrafiltration", "超滤不足");
        if ("MATCH".equals(status)) return text("Ultrafiltration on target", "超滤达标");
        return text("Status not determined", "状态待判定");
    }

    private List<Map<String, Object>> buildMonthlyTrend(Long userId, Long patientId) {
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate now = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
        for (int i = 5; i >= 0; i--) {
            String month = now.minusMonths(i).format(fmt);
            Map<String, Object> row = new HashMap<>();
            row.put("month", month);

            QueryWrapper<DialysisRecord> dq = new QueryWrapper<>();
            dataScopeHelper.applyUserScope(dq);
            if (patientId != null) {
                dq.eq("patient_id", patientId);
            }
            dq.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
            row.put("dialysis", dialysisRecordService.count(dq));

            QueryWrapper<MedicalRecord> mq = new QueryWrapper<>();
            dataScopeHelper.applyUserScope(mq);
            if (patientId != null) {
                mq.eq("patient_id", patientId);
            }
            mq.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
            row.put("medical", medicalRecordService.count(mq));

            QueryWrapper<MedicationLog> lq = new QueryWrapper<>();
            dataScopeHelper.applyUserScope(lq);
            if (patientId != null) {
                lq.eq("patient_id", patientId);
            }
            lq.apply("DATE_FORMAT(administration_time, '%Y-%m') = {0}", month);
            row.put("medication", medicationLogMapper.selectCount(lq));

            trend.add(row);
        }
        return trend;
    }

    private Map<String, Long> buildDehydrationStats(Long userId, String month, Long patientId) {
        QueryWrapper<DialysisRecord> qw = new QueryWrapper<>();
        dataScopeHelper.applyUserScope(qw);
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        qw.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
        List<DialysisRecord> records = dialysisRecordService.list(qw);
        long tooMuch = 0;
        long insufficient = 0;
        long match = 0;
        for (DialysisRecord r : records) {
            String status = r.getDehydrationStatus();
            if ("TOO_MUCH".equals(status)) {
                tooMuch++;
            } else if ("INSUFFICIENT".equals(status)) {
                insufficient++;
            } else if ("MATCH".equals(status)) {
                match++;
            }
        }
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("tooMuch", tooMuch);
        stats.put("insufficient", insufficient);
        stats.put("match", match);
        return stats;
    }

    private List<Map<String, Object>> buildDryWeightTrend(List<DryWeightMonthly> dryWeights) {
        if (dryWeights == null || dryWeights.isEmpty()) {
            return Collections.emptyList();
        }
        List<DryWeightMonthly> sorted = new ArrayList<>(dryWeights);
        Collections.reverse(sorted);
        int from = Math.max(0, sorted.size() - 12);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = from; i < sorted.size(); i++) {
            DryWeightMonthly d = sorted.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("month", d.getYearMonth());
            row.put("weight", d.getDryWeight());
            trend.add(row);
        }
        return trend;
    }
}
