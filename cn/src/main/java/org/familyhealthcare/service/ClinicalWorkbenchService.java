package org.familyhealthcare.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import org.familyhealthcare.dto.MedicalRecordReviewSummary;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State;
import org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ClinicalWorkbenchService {
    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordAlertProjection medicalAlerts;
    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard medicationTracking;
    @Autowired private org.familyhealthcare.service.familyassistance.FamilyAssistanceQueryService familyQueries;
    @Autowired private DataScopeHelper scope;
    @Autowired private PatientMapper patients;
    @Autowired private PatientClinicalMapper clinical;
    @Autowired private PatientHealthTargetMapper targets;
    @Autowired private AlertRecordMapper alerts;
    @Autowired private MedicationIntakeMapper intakes;
    @Autowired private MedicationMapper medications;
    @Autowired private CareItemMapper careItems;
    @Autowired private MedicalRecordMapper medicalRecords;
    @Autowired private MedicalRecordItemMapper medicalItems;
    @Autowired private DialysisRecordMapper dialysisRecords;
    @Autowired private AiAnalysisRecordMapper analysisRecords;
    @Autowired private MedicationStockService stockService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceTransactionManager medicalTransactions;
    @Autowired private SqlSessionFactory medicalSqlSessions;
    @Autowired private MedicalRecordReviewAccess medicalAccess;
    private MedicalReviewQualificationReader medicalQualification;
    @Autowired private void setMedicalQualification(MedicalReviewQualificationReader qualification) {medicalQualification = qualification;}

    public Map<String, Object> overview(Long patientId) {
        scope.requirePatient(patientId);
        long actor = scope.requireUserId();requireMedicalActor(actor);requireMedicalUnowned();
        Map<String, Object> out = new LinkedHashMap<>();
        MedicalIssues medical = null;boolean released = false;
        AlertOutput alertOutput=new AlertOutput(actor);
        try {
            org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead legacy=medicationTracking.legacyRead(patientId);List<Map<String,Object>> stockDisclosures=new ArrayList<>();
            out.put("attention", attention(patientId,legacy,stockDisclosures,alertOutput));
            medical = prepareMedicalIssues(patientId, actor);
            out.put("dataQuality", assembleDataQuality(patientId, medical));
            out.put("medicationSafety", medicationSafety(patientId,legacy));
            out.put("dialysisQuality", dialysisQuality(patientId, 90));
            out.put("emergencyCard", emergencyCard(patientId,legacy,alertOutput));
            out.put("generatedAt", LocalDateTime.now());
            stockService.finishLegacyRead(patientId,stockDisclosures);legacy.finish(scope.requireUserId());
            requireMedicalRelease(patientId, medical, actor);
            releaseAlerts(alertOutput);
            released = true;return out;
        } finally {if (!released) {clearMedicalIssues(medical);out.clear();}}
    }

    public Map<String, Object> attention(Long patientId) {return attention(patientId,medicationTracking.legacyRead(patientId),new ArrayList<>(),null);}
    private Map<String,Object> attention(Long patientId,org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead legacy,List<Map<String,Object>> stockDisclosures,AlertOutput staged){
        scope.requirePatient(patientId);
        boolean standalone=staged==null;AlertOutput alertOutput=standalone?new AlertOutput(scope.requireUserId()):staged;
        List<Map<String, Object>> items = new ArrayList<>();
        List<AlertRecord> activeAlerts = alerts.selectList(new QueryWrapper<AlertRecord>().apply("(alert_type IS NULL OR alert_type<>'MEASUREMENT_FOLLOW_UP')")
                .eq("patient_id", patientId).and(q -> q.isNull("status").or().ne("status", "RESOLVED"))
                .orderByDesc("triggered_at").last("limit 100"));
        for (AlertRecord alert : activeAlerts) {
            Map<String, Object> item = attentionItem("ALERT", alert.getAlertLevel(), alert.getAlertTitle(),
                    alert.getTriggeredValue(), alert.getTriggeredAt(), "复核并记录临床处置情况",
                    "/health-analysis?tab=alert", alert.getId());
            item.put("status", alert.getStatus());
            item.put("occurrenceCount", alert.getOccurrenceCount());
            alertOutput.rows.add(alert);alertOutput.copies.add(new AlertCopy(alert,item));
            items.add(item);
        }

        LocalDateTime now = LocalDateTime.now();
        List<MedicationIntake> medicationTasks = intakes.selectList(new QueryWrapper<MedicationIntake>()
                .eq("patient_id", patientId).in("status", "PENDING", "MISSED", "SNOOZED")
                .le("scheduled_at", now.plusHours(12)).orderByAsc("scheduled_at").last("limit 100"));
        medicationTasks=legacy.intakes(scope.requireUserId(),medicationTasks);
        Map<Long, Medication> medicationById = medications.selectList(new QueryWrapper<Medication>().eq("patient_id", patientId))
                .stream().collect(Collectors.toMap(Medication::getId, Function.identity(), (a, b) -> a));
        for (MedicationIntake task : medicationTasks) {
            Medication medication = medicationById.get(task.getMedicationId());
            if(task.getNextAction()!=null){
                Map<String,Object> typed=attentionItem("MEDICATION_WORKFLOW","INFO",medication==null?org.familyhealthcare.util.ExportLocalization.text("Medication follow-up","用药随访"):medication.getDrugName(),String.valueOf(task.getFollowUpState())+" / "+String.valueOf(task.getFollowUpKind()),null,org.familyhealthcare.util.ExportLocalization.text("Review recorded facts and current follow-up","核对已记录事实与当前随访"),"/medication?patientId="+patientId+(task.getExecutionId()==null?"":"&executionId="+task.getExecutionId()),task.getId());
                typed.remove("slaMinutes");items.add(typed);continue;
            }
            String severity = "MISSED".equals(task.getStatus()) ? "WARNING" : "INFO";
            items.add(attentionItem("MEDICATION", severity,
                    (medication == null ? "药品" : medication.getDrugName()) + " · " + intakeStatus(task.getStatus()),
                    task.getDosage(), task.getScheduledAt(), "确认已服、稍后提醒或记录原因",
                    "/family-health?tab=today", task.getId()));
        }

        List<Map<String,Object>> stockRows=stockService.list(patientId);stockDisclosures.addAll(stockRows);
        for (Map<String, Object> row : stockRows) {
            if (!Boolean.TRUE.equals(row.get("low"))) continue;
            if(row.get("nextAction")!=null){
                // 库存证据核对不能借旧提示变成补药或改处方建议，也不推断执行时限。
                Map<String,Object> typed=attentionItem("STOCK_WORKFLOW","WARNING",org.familyhealthcare.util.ExportLocalization.text("Stock evidence review · ","库存证据核对 · ")+row.get("drugName"),org.familyhealthcare.util.ExportLocalization.text("Recorded remaining ","记录余量 ")+row.get("quantity")+" "+row.get("unit"),null,org.familyhealthcare.util.ExportLocalization.text("Review the manual rule, current count and unresolved facts","核对人工规则、当前盘点与未核实事实"),"/care?patientId="+patientId+"&stockId="+row.get("id"),row.get("id"));
                typed.remove("slaMinutes");items.add(typed);continue;
            }
            items.add(attentionItem("STOCK", "WARNING", "药品库存不足 · " + row.get("drugName"),
                    "剩余 " + row.get("quantity") + " " + row.get("unit"), null,
                    "补充库存或修订当前医嘱", "/care", row.get("id")));
        }

        for (CareItem care : legacy.untrackedOrders(familyQueries.legacyItems(scope.requireUserId(),patientId,careItems.selectList(new QueryWrapper<CareItem>().eq("patient_id", patientId).ne("kind","QUESTION").orderByAsc("event_at"))))) {
            if (Arrays.asList("DONE", "COMPLETED", "CANCELLED", "RESOLVED").contains(care.getStatus())) continue;
            LocalDateTime due = care.getEventAt() != null ? care.getEventAt() : care.getNotifyAt();
            if (due == null || due.isAfter(now)) continue;
            Map<String, Object> item = attentionItem("CARE", "WARNING", care.getTitle(), care.getKind(), due,
                    "完成任务、重新指派或记录延期原因", "/care", care.getId());
            item.put("ownerId", care.getAssignedUserId() == null ? care.getUserId() : care.getAssignedUserId());
            items.add(item);
        }
        items.sort(Comparator.comparingInt(this::severityRank).thenComparing(row -> String.valueOf(row.get("dueAt"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", items.size());
        out.put("critical", items.stream().filter(x -> "CRITICAL".equals(x.get("severity"))).count());
        out.put("warning", items.stream().filter(x -> "WARNING".equals(x.get("severity"))).count());
        stockService.finishLegacyRead(patientId,stockRows);legacy.finish(scope.requireUserId());
        if(standalone)releaseAlerts(alertOutput);return out;
    }

    public Map<String, Object> dataQuality(Long patientId) {
        scope.requirePatient(patientId);
        long actor = scope.requireUserId();requireMedicalActor(actor);requireMedicalUnowned();
        MedicalIssues medical = null;boolean released = false;
        try {
            medical = prepareMedicalIssues(patientId, actor);
            Map<String, Object> out = assembleDataQuality(patientId, medical);
            requireMedicalRelease(patientId, medical, actor);
            released = true;return out;
        } finally {if (!released) clearMedicalIssues(medical);}
    }

    private Map<String, Object> assembleDataQuality(Long patientId, MedicalIssues medicalIssues) {
        List<Map<String, Object>> issues = medicalIssues.issues;
        List<DialysisRecord> dialysis = dialysisRecords.selectList(new QueryWrapper<DialysisRecord>()
                .eq("patient_id", patientId).orderByDesc("record_date").last("limit 60"));
        for (DialysisRecord row : dialysis) {
            if ("INCOMPLETE".equals(row.getRecordType()) || row.getSessionMinutes() == null || row.getKtv() == null && row.getUrr() == null) {
                issues.add(issue("DIALYSIS_COMPLETENESS", "INFO", "透析质量字段不完整",
                        String.valueOf(row.getRecordDate()) + " · 如有数据，请补充时长及 Kt/V 或 URR",
                        "dialysis", row.getId(), null));
            }
        }
        List<AiAnalysisRecord> drafts = analysisRecords.selectList(new QueryWrapper<AiAnalysisRecord>()
                .eq("patient_id", patientId).eq("review_status", "REVIEW_REQUIRED").orderByDesc("created_at").last("limit 50"));
        for (AiAnalysisRecord draft : drafts) {
            issues.add(issue("AI_DRAFT", "WARNING", "AI 分析草稿需要临床审核",
                    draft.getPeriodLabel(), "ai-analysis", draft.getId(), null));
        }
        Map<String, Object> out = medicalIssues.result;
        out.put("issues", issues);
        org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope medical=medicalValidity.open();
        out.put("invalidatedRecords",medical.ready()?medicalRecords.selectCount(new QueryWrapper<MedicalRecord>().eq("patient_id",patientId).apply("NOT ("+medical.activeSql("medical_record.")+")")):0L);
        out.put("pendingReview", issues.stream().filter(x -> Arrays.asList("REVIEW", "SOURCE_REVIEW", "DOCTOR_REVIEW", "AI_DRAFT").contains(x.get("type"))).count());
        out.put("lowConfidence", issues.stream().filter(x -> "LOW_CONFIDENCE".equals(x.get("type"))).count());
        out.put("total", issues.size());
        return out;
    }

    private void stageMedicalIssues(MedicalIssues medical, Map<Long, State> current) {
        List<Map<String, Object>> issues = medical.issues;
        List<MedicalRecord> records = medical.records;
        boolean unavailable = false;
        Map<String, List<MedicalRecord>> externalIds = records.stream()
                .filter(r -> r.getSourceExternalId() != null && !r.getSourceExternalId().trim().isEmpty())
                .collect(Collectors.groupingBy(r -> r.getSourceType() + ":" + r.getSourceExternalId()));
        for (MedicalRecord record : records) {
            State state = current.get(record.getId());
            MedicalRecordReviewSummary summary = MedicalRecordReviewSummary.fromObservedState(state);
            if (summary == null) throw medicalChanged();
            if ("UNAVAILABLE".equals(summary.getAvailability())) unavailable = true;
            if (!state.reviewParticipating) {
                if ("REVIEW_REQUIRED".equals(record.getVerificationStatus())) {
                    Map<String, Object> row = issue("REVIEW", "WARNING", "导入记录需要审核", recordLabel(record),
                            "medical-record", record.getId(), record.getConfidenceScore());
                    row.put("reviewChannel", "LEGACY");row.put("reviewSummary", summary);issues.add(row);
                }
            } else {
                MedicalRecordReviewSummary.Channel source = summary.getSource(), doctor = summary.getDoctor();
                if (source == null || !"APPROVED".equals(source.getState())) {
                    String title, detail;
                    if (source == null) {title = "来源等待核实";detail = "来源等待核实，不用于自动分析。";}
                    else if ("PENDING".equals(source.getState())) {title = "来源核对待处理";detail = "来源核对尚待处理，不用于自动分析。";}
                    else if ("REJECTED".equals(source.getState())) {title = "来源核对已拒绝";detail = "来源核对已拒绝，不用于自动分析。";}
                    else if ("SUPERSEDED".equals(source.getState())) {title = "来源在核对后发生变更";detail = "来源在核对后发生变更，不用于自动分析。";}
                    else throw medicalChanged();
                    issues.add(reviewIssue("SOURCE_REVIEW", "WARNING", title, detail, record, "SOURCE", summary));
                }
                if (doctor != null && ("PENDING".equals(doctor.getState()) || "REJECTED".equals(doctor.getState()))) {
                    boolean pending = "PENDING".equals(doctor.getState());
                    String detail;
                    if (pending) detail = "医生通道的复核尚待处理。";
                    else if ("ADMIN".equals(doctor.getActorKind())) detail = "由管理员 #" + doctor.getActorId() + " 拒绝。";
                    else if ("ASSIGNED_DOCTOR".equals(doctor.getActorKind())) detail = "由已分配医生 #" + doctor.getActorId() + " 拒绝。";
                    else throw medicalChanged();
                    if (source == null) detail += " 来源等待核实，不用于自动分析。";
                    issues.add(reviewIssue(pending ? "DOCTOR_REVIEW" : "DOCTOR_REVIEW_OUTCOME", pending ? "WARNING" : "INFO",
                            pending ? "临床复核待处理" : "临床复核已拒绝", detail, record, "DOCTOR", summary));
                }
            }
            if (record.getConfidenceScore() != null && record.getConfidenceScore().compareTo(new BigDecimal("0.80")) < 0) {
                issues.add(issue("LOW_CONFIDENCE", "WARNING", "识别置信度较低", recordLabel(record),
                        "medical-record", record.getId(), record.getConfidenceScore()));
            }
            if (record.getRecordDate() == null || record.getHospitalName() == null || record.getHospitalName().trim().isEmpty()) {
                issues.add(issue("MISSING_CONTEXT", "INFO", "记录上下文不完整",
                        recordLabel(record) + " · 请补充日期和医疗机构", "medical-record", record.getId(), null));
            }
        }
        for (Map.Entry<String, List<MedicalRecord>> entry : externalIds.entrySet()) {
            if (entry.getValue().size() > 1) {
                issues.add(issue("DUPLICATE", "WARNING", "可能存在重复导入记录",
                        entry.getKey() + " 出现 " + entry.getValue().size() + " 次", "medical-record",
                        entry.getValue().get(0).getId(), null));
            }
        }
        if (unavailable) {
            Map<String, Object> row = issue("REVIEW_AVAILABILITY", "INFO", "核对状态不可用",
                    "当前来源核对与临床复核信息不可用；原有审核标记另行显示。", "medical-record", null, null);
            row.put("reviewChannel", null);row.put("reviewSummary", null);issues.add(row);
        }
    }

    private Map<String, Object> reviewIssue(String type, String severity, String title, String detail,
            MedicalRecord record, String channel, MedicalRecordReviewSummary summary) {
        Map<String, Object> row = issue(type, severity, title, recordLabel(record) + " · " + detail,
                "medical-record", record.getId(), null);
        row.put("reviewChannel", channel);row.put("reviewSummary", summary);return row;
    }

    /** The unchanged candidate set is staged under an explicit, completed RR owner. */
    private MedicalIssues prepareMedicalIssues(Long patientId, long actor) {
        requireMedicalActor(actor);requireMedicalUnowned();
        MedicalIssues staged = new MedicalIssues();
        RuntimeException[] diagnosed = new RuntimeException[1];
        int[] completion = {TransactionSynchronization.STATUS_UNKNOWN};
        MedicalIssues result;
        try {
            result = medicalTemplate().execute(transaction -> {
                MedicalResource resource = new MedicalResource(actor);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {resource.verify();}
                    @Override public void afterCompletion(int status) {
                        completion[0] = status;
                        if (status != STATUS_COMMITTED) clearMedicalIssues(staged);
                    }
                });
                try {
                    MedicalRecordValidityProjection.Scope medical = medicalValidity.open();resource.verify();
                    staged.records.addAll(medicalRecords.selectList(new QueryWrapper<MedicalRecord>()
                            .eq("patient_id", patientId).apply(medical.activeSql("medical_record.")).orderByDesc("record_date").last("limit 300")));
                    for (MedicalRecord record : staged.records) if (record != null) record.setReviewSummary(null);
                    resource.verify();
                    for (MedicalRecord record : staged.records) {
                        MedicalHeader header = new MedicalHeader(record);
                        if (!Objects.equals(patientId, header.patient)) throw medicalChanged();
                        MedicalHeader prior = staged.headers.putIfAbsent(header.id, header);
                        if (prior != null && !prior.same(header)) throw medicalChanged();
                    }
                    for (MedicalHeader header : staged.headers.values()) staged.sources.add(header.record());
                    try {medicalAccess.requireReadAdmission(staged.sources, actor);}
                    catch (WorkflowException failure) {throw failure;}
                    catch (IllegalStateException failure) {throw new MedicalDenied();}
                    finally {resource.verify();}
                    List<Long> ids = new ArrayList<>(staged.headers.keySet());
                    for (int offset = 0; offset < Math.max(1, ids.size()); offset += 100) {
                        resource.verify();List<Long> part = ids.subList(offset, Math.min(offset + 100, ids.size()));
                        Map<Long, State> batch;
                        try {batch = medical.qualifiedSnapshot(part);}
                        finally {resource.verify();}
                        if (batch == null || !batch.keySet().equals(new LinkedHashSet<>(part))) throw medicalChanged();
                        for (Long id : part) {
                            requireMedicalState(staged.headers.get(id), batch.get(id), MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT);
                            staged.snapshotStates.put(id, batch.get(id));
                        }
                    }
                    Map<Long, State> current = currentMedicalFrame(staged, actor, resource);
                    stageMedicalIssues(staged, current);resource.verify();return staged;
                } catch (WorkflowException | MedicalDenied failure) {
                    resource.verify();diagnosed[0] = failure;throw failure;
                } catch (RuntimeException failure) {
                    resource.verify();WorkflowException safe = medicalUnavailable();diagnosed[0] = safe;throw safe;
                } catch (Error failure) {resource.verify();throw failure;}
            });
        } catch (RuntimeException failure) {
            clearMedicalIssues(staged);requireMedicalUnowned();requireMedicalActor(actor);
            if (failure == diagnosed[0] && completion[0] == TransactionSynchronization.STATUS_ROLLED_BACK) throw failure;
            throw medicalUnavailable();
        } catch (Error failure) {
            clearMedicalIssues(staged);requireMedicalUnowned();requireMedicalActor(actor);throw failure;
        }
        try {
            requireMedicalUnowned();requireMedicalActor(actor);
            if (result != staged || completion[0] != TransactionSynchronization.STATUS_COMMITTED) throw medicalUnavailable();
            return result;
        } catch (RuntimeException | Error failure) {clearMedicalIssues(staged);throw failure;}
    }

    /** F2 follows every enclosing operation, including both overview legacy finish calls. */
    private void requireMedicalRelease(Long patientId, MedicalIssues medical, long actor) {
        requireMedicalUnowned();requireMedicalActor(actor);
        scope.requirePatient(patientId);
        currentMedicalFrame(medical, actor, null);
        requireMedicalActor(actor);requireMedicalUnowned();
    }

    private Map<Long, State> currentMedicalFrame(MedicalIssues medical, long actor, MedicalResource outer) {
        requireMedicalActor(actor);
        try {
            Map<Long, State> current = medicalAccess.currentReadFrame(medical.sources, medical.snapshotStates, actor);
            if (current == null || !current.keySet().equals(medical.headers.keySet())) throw medicalChanged();
            for (MedicalHeader header : medical.headers.values()) {
                State state = current.get(header.id);
                requireMedicalState(header, state, MedicalReviewQualificationReader.Semantics.CURRENT_CHECK);
                if (!state.signature.equals(medical.snapshotStates.get(header.id).signature)) throw medicalChanged();
            }
            return current;
        } catch (WorkflowException failure) {throw failure;}
        catch (IllegalStateException failure) {throw new MedicalDenied();}
        catch (RuntimeException failure) {throw medicalUnavailable();}
        finally {
            if (outer != null) outer.verify();else requireMedicalUnowned();
            requireMedicalActor(actor);
        }
    }

    private TransactionTemplate medicalTemplate() {
        medicalGraph();TransactionTemplate template = new TransactionTemplate(medicalTransactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);template.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);return template;
    }
    private void medicalGraph() {
        try {
            if (jdbc == null || jdbc.getDataSource() == null || medicalTransactions == null || medicalTransactions.getDataSource() != jdbc.getDataSource()
                    || medicalValidity == null || medicalAccess == null || medicalQualification == null || medicalSqlSessions == null
                    || medicalSqlSessions.getConfiguration().getEnvironment().getDataSource() != jdbc.getDataSource()
                    || !(medicalSqlSessions.getConfiguration().getEnvironment().getTransactionFactory() instanceof SpringManagedTransactionFactory)) throw medicalUnavailable();
            medicalValidity.requireDataSource(jdbc.getDataSource());medicalAccess.requireDataSource(jdbc.getDataSource());medicalQualification.requireDataSource(jdbc.getDataSource());
        } catch (RuntimeException failure) {throw medicalUnavailable();}
    }
    private void requireMedicalUnowned() {
        medicalGraph();
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(jdbc.getDataSource()) || TransactionSynchronizationManager.hasResource(medicalSqlSessions)) throw medicalUnavailable();
    }
    private static void requireMedicalActor(long actor) {
        if (!safeMedicalId(actor) || !Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw new MedicalDenied();
    }
    private final class MedicalResource {
        final Thread thread = Thread.currentThread();final long actor;
        final ConnectionHolder holder;final SimpleConnectionHandle handle;final Connection connection;
        MedicalResource(long actor) {
            this.actor = actor;requireMedicalActor(actor);medicalGraph();
            Object value = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                    || value == null || value.getClass() != ConnectionHolder.class) throw medicalUnavailable();
            holder = (ConnectionHolder)value;
            if (!holder.isSynchronizedWithTransaction() || holder.isRollbackOnly() || holder.getConnectionHandle() == null
                    || holder.getConnectionHandle().getClass() != SimpleConnectionHandle.class) throw medicalUnavailable();
            handle = (SimpleConnectionHandle)holder.getConnectionHandle();connection = handle.getConnection();verify();
        }
        void verify() {
            requireMedicalActor(actor);medicalGraph();
            if (Thread.currentThread() != thread || !TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly() || TransactionSynchronizationManager.getResource(jdbc.getDataSource()) != holder
                    || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly() || holder.getConnectionHandle() != handle || handle.getConnection() != connection) throw medicalUnavailable();
            try {
                if (connection == null || holder.getConnection() != connection || connection.isClosed() || connection.getAutoCommit()
                        || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) throw medicalUnavailable();
            } catch (java.sql.SQLException | IllegalStateException failure) {throw medicalUnavailable();}
        }
    }
    private static void requireMedicalState(MedicalHeader header, State state, MedicalReviewQualificationReader.Semantics semantics) {
        if (header == null || state == null || !state.reviewObserved || state.signature == null || state.checkedAt == null
                || state.qualificationSemantics != semantics || state.id != header.id || !Objects.equals(state.patientId, header.patient)
                || !Objects.equals(state.ownerId, header.owner) || !Objects.equals(state.version, header.version)
                || !Objects.equals(state.review, header.review) || !state.active()) throw medicalChanged();
    }
    private static boolean safeMedicalId(Long value) {return value != null && value > 0 && value <= 9007199254740991L;}
    private static WorkflowException medicalUnavailable() {return new WorkflowException(503, "CURRENT_RESULT_UNAVAILABLE", "The current medical record result could not be verified.");}
    private static WorkflowException medicalChanged() {return new WorkflowException(409, "CURRENT_RESULT_UNAVAILABLE", "The current medical record result could not be verified.");}
    private static final class MedicalDenied extends IllegalStateException {MedicalDenied() {super("Access denied to source record.");}}
    private static void clearMedicalIssues(MedicalIssues medical) {
        if (medical == null) return;
        for (MedicalRecord record : medical.records) if (record != null) record.setReviewSummary(null);
        for (Map<String, Object> row : medical.issues) {row.remove("reviewChannel");row.remove("reviewSummary");}
        medical.issues.clear();medical.result.clear();
    }
    private static final class MedicalHeader {
        final long id;final Long patient, owner, version;final String review;
        MedicalHeader(MedicalRecord record) {
            if (record == null || !safeMedicalId(record.getId()) || !safeMedicalId(record.getContentVersion())
                    || record.getPatientId() != null && !safeMedicalId(record.getPatientId())
                    || record.getUserId() != null && !safeMedicalId(record.getUserId())) throw medicalChanged();
            id = record.getId();patient = record.getPatientId();owner = record.getUserId();version = record.getContentVersion();review = record.getVerificationStatus();
        }
        boolean same(MedicalHeader other) {return id == other.id && Objects.equals(patient, other.patient) && Objects.equals(owner, other.owner) && Objects.equals(version, other.version) && Objects.equals(review, other.review);}
        MedicalRecord record() {
            MedicalRecord record = new MedicalRecord();record.setId(id);record.setPatientId(patient);record.setUserId(owner);record.setContentVersion(version);record.setVerificationStatus(review);return record;
        }
    }
    private static final class MedicalIssues {
        final List<MedicalRecord> records = new ArrayList<>(), sources = new ArrayList<>();
        final Map<Long, MedicalHeader> headers = new LinkedHashMap<>();final Map<Long, State> snapshotStates = new LinkedHashMap<>();
        final List<Map<String, Object>> issues = new ArrayList<>();final Map<String, Object> result = new LinkedHashMap<>();
    }

    public Map<String, Object> medicationSafety(Long patientId) {return medicationSafety(patientId,medicationTracking.legacyRead(patientId));}
    private Map<String,Object> medicationSafety(Long patientId,org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead legacy){
        scope.requirePatient(patientId);
        List<Medication> active = medications.selectList(new QueryWrapper<Medication>()
                .eq("patient_id", patientId).eq("is_active", 1).orderByAsc("drug_name"));
        active=legacy.medications(scope.requireUserId(),active);
        PatientClinical profile = clinical.selectOne(new QueryWrapper<PatientClinical>().eq("patient_id", patientId).last("limit 1"));
        String allergies = profile == null || profile.getAllergyDrugs() == null ? "" : profile.getAllergyDrugs().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, List<Medication>> byGeneric = active.stream().collect(Collectors.groupingBy(this::normalizedMedicationName));
        for (Map.Entry<String, List<Medication>> entry : byGeneric.entrySet()) {
            if (!entry.getKey().isEmpty() && entry.getValue().size() > 1) {
                findings.add(safetyFinding("DUPLICATE", "WARNING", "可能存在重复用药",
                        entry.getValue().stream().map(Medication::getDrugName).collect(Collectors.joining(" + ")),
                        "请确认这两条启用记录是否均为预期医嘱。"));
            }
        }
        for (Medication medication : active) {
            String name = normalizedMedicationName(medication);
            if (!name.isEmpty() && allergies.contains(name)) {
                findings.add(safetyFinding("ALLERGY", "CRITICAL", "药品与已记录的过敏清单匹配",
                        medication.getDrugName(), "请勿在平台内自行变更治疗方案，应尽快联系开方医生或药师。"));
            }
            String haystack = (medication.getDrugName() + " " + medication.getGenericName() + " " + medication.getCategory()).toLowerCase(Locale.ROOT);
            if (haystack.contains("nsaid") || haystack.contains("ibuprofen") || haystack.contains("naproxen")) {
                findings.add(safetyFinding("RENAL", "WARNING", "建议复核肾功能剂量与用药安全",
                        medication.getDrugName(), "NSAIDs 可能不适合晚期肾病患者，请核实开方方案。"));
            }
        }
        List<Map<String, Object>> rules = jdbc.queryForList("SELECT * FROM medication_safety_rule WHERE enabled=1 ORDER BY id");
        Set<String> names = active.stream().flatMap(m -> Arrays.stream((m.getDrugName() + " " + m.getGenericName()).toLowerCase(Locale.ROOT).split("[,/+]")))
                .map(String::trim).collect(Collectors.toSet());
        for (Map<String, Object> rule : rules) {
            String a = String.valueOf(rule.get("ingredient_a")).toLowerCase(Locale.ROOT);
            String b = String.valueOf(rule.get("ingredient_b")).toLowerCase(Locale.ROOT);
            if (containsMedication(names, a) && containsMedication(names, b)) {
                findings.add(safetyFinding("INTERACTION", String.valueOf(rule.get("severity")),
                        "潜在药物相互作用", a + " + " + b,
                        String.valueOf(rule.get("message")) + (rule.get("renal_note") == null ? "" : " " + rule.get("renal_note"))));
            }
        }
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<MedicationIntake> recent = intakes.selectList(new QueryWrapper<MedicationIntake>()
                .eq("patient_id", patientId).ge("scheduled_at", since));
        recent=legacy.untrackedIntakes(recent); // B 的未知/仅随访/真实结束不能参加旧依从率分母。
        long taken = recent.stream().filter(x -> "TAKEN".equals(x.getStatus())).count();
        long missed = recent.stream().filter(x -> Arrays.asList("MISSED", "SKIPPED").contains(x.getStatus())).count();
        BigDecimal adherence = taken + missed == 0 ? null : BigDecimal.valueOf(taken * 100.0 / (taken + missed)).setScale(1, RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("medications", active);
        out.put("allergies", profile == null ? null : profile.getAllergyDrugs());
        out.put("findings", findings);
        out.put("adherenceRate", adherence);
        out.put("taken", taken);
        out.put("missedOrSkipped", missed);
        out.put("disclaimer", "筛查结果仅用于辅助决策，任何用药变更都必须由医生或药师确认。");
        legacy.finish(scope.requireUserId());return out;
    }

    public Map<String, Object> dialysisQuality(Long patientId, int days) {
        scope.requirePatient(patientId);
        int range = Math.max(7, Math.min(days, 365));
        PatientClinical profile = clinical.selectOne(new QueryWrapper<PatientClinical>().eq("patient_id", patientId).last("limit 1"));
        BigDecimal dryWeight = profile == null ? null : profile.getTargetDryWeight();
        List<DialysisRecord> rows = dialysisRecords.selectList(new QueryWrapper<DialysisRecord>()
                .eq("patient_id", patientId).ge("record_date", LocalDate.now().minusDays(range)).orderByAsc("record_date"));
        List<Map<String, Object>> sessions = new ArrayList<>();
        List<BigDecimal> idwgValues = new ArrayList<>(), ufrValues = new ArrayList<>(), ktvValues = new ArrayList<>(), urrValues = new ArrayList<>();
        int accessIssues = 0;
        for (DialysisRecord row : rows) {
            BigDecimal idwgPercent = dryWeight == null || dryWeight.signum() == 0 || row.getWeightGain() == null ? null
                    : row.getWeightGain().multiply(BigDecimal.valueOf(100)).divide(dryWeight, 2, RoundingMode.HALF_UP);
            BigDecimal ufr = dryWeight == null || dryWeight.signum() == 0 || row.getUfAmount() == null || row.getSessionMinutes() == null || row.getSessionMinutes() <= 0 ? null
                    : row.getUfAmount().multiply(BigDecimal.valueOf(60000)).divide(dryWeight.multiply(BigDecimal.valueOf(row.getSessionMinutes())), 2, RoundingMode.HALF_UP);
            if (idwgPercent != null) idwgValues.add(idwgPercent);
            if (ufr != null) ufrValues.add(ufr);
            if (row.getKtv() != null) ktvValues.add(row.getKtv());
            if (row.getUrr() != null) urrValues.add(row.getUrr());
            if (row.getAccessIssue() != null && !row.getAccessIssue().trim().isEmpty()) accessIssues++;
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("id", row.getId()); session.put("date", row.getRecordDate()); session.put("idwgPercent", idwgPercent);
            session.put("ufr", ufr); session.put("ktv", row.getKtv()); session.put("urr", row.getUrr());
            session.put("accessIssue", row.getAccessIssue());
            sessions.add(session);
        }
        List<Map<String, Object>> flags = new ArrayList<>();
        BigDecimal avgIdwg = average(idwgValues), avgUfr = average(ufrValues), avgKtv = average(ktvValues), avgUrr = average(urrValues);
        if (avgIdwg != null && avgIdwg.compareTo(new BigDecimal("4.0")) > 0) flags.add(metricFlag("IDWG", "WARNING", "平均透析间期增重超过目标干体重的 4%。"));
        if (avgUfr != null && avgUfr.compareTo(new BigDecimal("13")) > 0) flags.add(metricFlag("UFR", "CRITICAL", "平均超滤率超过 13 mL/kg/小时。"));
        if (avgKtv != null && avgKtv.compareTo(new BigDecimal("1.2")) < 0) flags.add(metricFlag("KT/V", "WARNING", "记录的平均 Kt/V 低于 1.2。"));
        if (avgUrr != null && avgUrr.compareTo(new BigDecimal("65")) < 0) flags.add(metricFlag("URR", "WARNING", "记录的平均 URR 低于 65%。"));
        if (accessIssues > 0) flags.add(metricFlag("ACCESS", "WARNING", accessIssues + " 次透析存在血管通路问题。"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rangeDays", range); out.put("sessions", sessions); out.put("sessionCount", rows.size());
        out.put("avgIdwgPercent", avgIdwg); out.put("avgUfr", avgUfr); out.put("avgKtv", avgKtv); out.put("avgUrr", avgUrr);
        out.put("accessIssueCount", accessIssues); out.put("flags", flags);
        out.put("scheduleWeekdays", profile == null ? null : profile.getDialysisWeekdays());
        out.put("scheduleTime", profile == null ? null : profile.getDialysisTime());
        out.put("disclaimer", "阈值仅用于风险筛查，并非治疗目标；应由透析团队结合临床情况解读。");
        return out;
    }

    public Map<String, Object> emergencyCard(Long patientId) {return emergencyCard(patientId,medicationTracking.legacyRead(patientId),null);}
    private Map<String,Object> emergencyCard(Long patientId,org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead legacy,AlertOutput staged){
        Patient patient = scope.requirePatient(patientId);
        boolean standalone=staged==null;AlertOutput alertOutput=standalone?new AlertOutput(scope.requireUserId()):staged;
        PatientClinical profile = clinical.selectOne(new QueryWrapper<PatientClinical>().eq("patient_id", patientId).last("limit 1"));
        PatientHealthTarget target = targets.selectOne(new QueryWrapper<PatientHealthTarget>().eq("patient_id", patientId).last("limit 1"));
        DialysisRecord latest = dialysisRecords.selectOne(new QueryWrapper<DialysisRecord>().eq("patient_id", patientId).orderByDesc("record_date").last("limit 1"));
        List<Medication> active = medications.selectList(new QueryWrapper<Medication>().eq("patient_id", patientId).eq("is_active", 1).orderByAsc("drug_name"));
        active=legacy.medications(scope.requireUserId(),active);
        List<AlertRecord> unresolved = alerts.selectList(new QueryWrapper<AlertRecord>().apply("(alert_type IS NULL OR alert_type<>'MEASUREMENT_FOLLOW_UP')").eq("patient_id", patientId)
                .and(q -> q.isNull("status").or().ne("status", "RESOLVED")).orderByDesc("triggered_at").last("limit 10"));
        alertOutput.rows.addAll(unresolved);
        Map<String, Object> safePatient = new LinkedHashMap<>();
        safePatient.put("id", patient.getId()); safePatient.put("name", patient.getName()); safePatient.put("gender", patient.getGender());
        safePatient.put("birthDate", patient.getBirthDate()); safePatient.put("phone", patient.getPhone());
        safePatient.put("emergencyContact", patient.getEmergencyContact()); safePatient.put("emergencyPhone", patient.getEmergencyPhone());
        safePatient.put("medicalHistory", patient.getMedicalHistory());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", safePatient); out.put("clinical", profile); out.put("target", target); out.put("medications", active);
        out.put("latestDialysis", latest); out.put("unresolvedAlerts", unresolved); out.put("generatedAt", LocalDateTime.now());
        out.put("disclaimer", "仅用于急救沟通。请与患者或诊疗团队核验身份、用药清单和过敏史。");
        legacy.finish(scope.requireUserId());
        if(standalone)releaseAlerts(alertOutput);return out;
    }

    /** Invocation-local original occurrences and deferred map copies; qualification remains owned by J1. */
    private static final class AlertOutput {
        final long actor;final List<AlertRecord> rows=new ArrayList<>();final List<AlertCopy> copies=new ArrayList<>();
        AlertOutput(long actor){this.actor=actor;}
    }
    private static final class AlertCopy {
        final AlertRecord row;final Map<String,Object> output;
        AlertCopy(AlertRecord row,Map<String,Object> output){this.row=row;this.output=output;}
    }
    private void releaseAlerts(AlertOutput staged) {
        requireMedicalActor(staged.actor);medicalAlerts.project(staged.rows);
        for(AlertCopy copy:staged.copies) {
            AlertRecord row=copy.row;
            if(!"MEDICAL_RECORD_ITEM".equals(row.getSourceType())||"MEASUREMENT_FOLLOW_UP".equals(row.getAlertType()))continue;
            copy.output.put("sourceStatus",row.getSourceStatus());
            copy.output.put("sourceBindingStatus",row.getSourceBindingStatus());
            copy.output.put("currentSourceStatus",row.getCurrentSourceStatus());
            copy.output.put("sourceQualificationSemantics",row.getSourceQualificationSemantics());
            copy.output.put("sourceQualificationAt",row.getSourceQualificationAt());
        }
    }

    private Map<String, Object> attentionItem(String type, String severity, String title, Object evidence,
                                               LocalDateTime dueAt, String action, String path, Object sourceId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type); row.put("severity", severity == null ? "INFO" : severity); row.put("title", title);
        row.put("evidence", evidence); row.put("dueAt", dueAt); row.put("recommendedAction", action);
        row.put("path", path); row.put("sourceId", sourceId);
        row.put("slaMinutes", "CRITICAL".equals(severity) ? 15 : "WARNING".equals(severity) ? 240 : 1440);
        return row;
    }

    private int severityRank(Map<String, Object> row) {
        return "CRITICAL".equals(row.get("severity")) ? 0 : "WARNING".equals(row.get("severity")) ? 1 : 2;
    }

    private Map<String, Object> issue(String type, String severity, String title, String detail,
                                      String sourceType, Long sourceId, BigDecimal confidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type); row.put("severity", severity); row.put("title", title); row.put("detail", detail);
        row.put("sourceType", sourceType); row.put("sourceId", sourceId); row.put("confidence", confidence);
        return row;
    }

    private String recordLabel(MedicalRecord record) {
        return (record.getRecordDate() == null ? "日期缺失" : record.getRecordDate()) + " · "
                + (record.getHospitalName() == null ? "医疗机构缺失" : record.getHospitalName());
    }

    private String intakeStatus(String status) {
        if ("TAKEN".equals(status)) return "已服用";
        if ("MISSED".equals(status)) return "已错过";
        if ("SNOOZED".equals(status)) return "已稍后提醒";
        if ("SKIPPED".equals(status)) return "已跳过";
        return "待处理";
    }

    private String normalizedMedicationName(Medication medication) {
        String value = medication.getGenericName() == null || medication.getGenericName().trim().isEmpty()
                ? medication.getDrugName() : medication.getGenericName();
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsMedication(Set<String> names, String needle) {
        return names.stream().anyMatch(name -> name.contains(needle) || needle.contains(name));
    }

    private Map<String, Object> safetyFinding(String type, String severity, String title, String evidence, String action) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type); row.put("severity", severity); row.put("title", title); row.put("evidence", evidence); row.put("action", action);
        return row;
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> metricFlag(String metric, String severity, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric", metric); row.put("severity", severity); row.put("message", message);
        return row;
    }
}
