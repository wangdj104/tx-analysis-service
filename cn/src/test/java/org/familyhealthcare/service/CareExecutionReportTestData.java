package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.CareExecutionReport;
import org.familyhealthcare.service.careplan.CareExecutionReportContracts;

import java.time.Instant;
import java.util.*;

/** Clearly fictional, reusable report data; no patient or clinical source is read. */
public final class CareExecutionReportTestData {
    private CareExecutionReportTestData() { }

    /** Fixed IDs and explicit synthetic prose, reusable in H2 and isolated MySQL fixtures. */
    public static Map<String, Long> seedReportScenario(org.springframework.jdbc.core.JdbcTemplate jdbc, Instant now) {
        Map<String, Long> ids = new LinkedHashMap<>();
        ids.put("plan", 100L); ids.put("oldRevision", 100L); ids.put("revision", 101L);
        ids.put("olderHelp", 103L); ids.put("resubmitted", 104L); ids.put("returned", 102L);
        ids.put("historicalReceipt", 1001L);
        for (long plan : new long[]{100, 200, 300, 400}) {
            String life = plan == 200 ? "COMPLETED" : plan == 300 ? "CANCELLED" : plan == 400 ? "DRAFT" : "ACTIVE";
            jdbc.update("INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,instructions,workflow_version,lifecycle) VALUES(?,9,1,'Synthetic mutable title','Synthetic mutable instructions',1,?)", plan, life);
            jdbc.update("INSERT INTO care_plan_revision(id,plan_id,revision_no,status,title,instructions,plan_type,draft_json,created_by,created_at,updated_at,published_at) VALUES(?,?,1,?,'Synthetic original title','Synthetic original instructions','OTHER','{\"actions\":[]}',9,?,?,?)",
                    plan, plan, plan == 400 ? "DRAFT" : "PUBLISHED", utc(now), utc(now), plan == 400 ? null : utc(now));
            jdbc.update("UPDATE doctor_care_plan SET " + (plan == 400 ? "draft_revision_id" : "current_revision_id") + "=? WHERE id=?", plan, plan);
        }
        jdbc.update("INSERT INTO care_plan_revision(id,plan_id,revision_no,status,title,instructions,plan_type,draft_json,created_by,created_at,updated_at,published_at) VALUES(101,100,2,'PUBLISHED','Synthetic current title','原文：新版指示','OTHER',?,9,?,?,?)",
                "{\"actions\":[{\"ordinal\":1,\"evidence\":[{\"sourceType\":\"MEASUREMENT\",\"sourceId\":501},{\"sourceType\":\"MEDICAL_RECORD\",\"sourceId\":502}]}]}",
                utc(now), utc(now), utc(now));
        jdbc.update("UPDATE doctor_care_plan SET current_revision_id=101 WHERE id=100");
        String[] states = {"OPEN","OPEN","NEEDS_HELP","SUBMITTED","SUBMITTED","CONFIRMED"};
        for (int i=0;i<6;i++) {
            jdbc.update("INSERT INTO care_plan_action(id,plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,status,review_waiting_since,created_at,updated_at) VALUES(?,100,101,1,?,'Synthetic current action',?,7,?,?,?,?)",
                    101+i, i+1, utc(now.minusSeconds(86400)), states[i], i==3||i==4 ? utc(now.minusSeconds(60)) : null, utc(now), utc(now));
        }
        for (long id : new long[]{100,200,300,400}) jdbc.update("INSERT INTO care_plan_action(id,plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,status,created_at,updated_at) VALUES(?,?,?,1,1,'Synthetic old action',?,7,'CONFIRMED',?,?)", id,id,id,utc(now),utc(now),utc(now));
        seedEvent(jdbc,1001,100,100,100L,"RECEIPT_SUBMITTED",now.minusSeconds(3600),now.minusSeconds(86400*40),"{\"actionStatus\":\"SUBMITTED\"}");
        seedEvent(jdbc,1002,100,101,102L,"RECEIPT_SUBMITTED",now.minusSeconds(300),now.minusSeconds(600),"{}");
        seedEvent(jdbc,1003,100,101,102L,"RECEIPT_RETURNED",now.minusSeconds(200),null,"{}");
        seedEvent(jdbc,1004,100,101,104L,"RECEIPT_SUBMITTED",now.minusSeconds(300),now.minusSeconds(600),"{}");
        seedEvent(jdbc,1005,100,101,104L,"RECEIPT_RETURNED",now.minusSeconds(120),null,"{}");
        seedEvent(jdbc,1006,100,101,104L,"RECEIPT_SUBMITTED",now.minusSeconds(60),now.minusSeconds(100),"{}");
        seedEvent(jdbc,1007,100,101,103L,"HELP_REQUESTED",now.minusSeconds(86400*40),null,"{}");
        seedEvent(jdbc,1008,100,101,103L,"FOLLOW_UP_RECORDED",now.minusSeconds(86400*39),null,"{\"kind\":\"CONTACTED\",\"actionStatus\":\"NEEDS_HELP\"}");
        seedEvent(jdbc,1009,200,200,null,"PLAN_CLOSED",now.minusSeconds(30),null,"{}");
        seedEvent(jdbc,1010,300,300,null,"PLAN_CANCELLED",now.minusSeconds(20),null,"{}");
        seedEvent(jdbc,1011,400,400,null,"DRAFT_SAVED",now.minusSeconds(10),null,"{}");
        seedEvent(jdbc,1012,400,400,400L,"RECEIPT_SUBMITTED",now.minusSeconds(9),now.minusSeconds(10),"{}");
        seedEvent(jdbc,1013,100,101,102L,"FOLLOW_UP_RECORDED",now.minusSeconds(10),null,"{\"kind\":\"AWAITING_INFORMATION\",\"actionStatus\":\"OPEN\"}");
        jdbc.update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(501,1,7,'CUSTOM',99,'synthetic',?)", utc(now));
        jdbc.update("INSERT INTO medical_record(id,patient_id,user_id,patient_name,record_type) VALUES(502,1,7,'Synthetic patient','OTHER')");
        jdbc.update("INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(1006,'MEASUREMENT',501),(1001,'MEDICAL_RECORD',502),(1006,'MEDICAL_RECORD',999)");
        jdbc.update("INSERT INTO care_item(id,patient_id,user_id,kind,title,status,actor_id,actor_name,event_at,created_at,updated_at,data_json) VALUES(601,1,7,'QUESTION','Synthetic question','OPEN',7,'Synthetic owner','2026-08-01 09:00:00','2026-08-01 08:00:00',NULL,?)",
                "{\"description\":\"原文：待讨论\",\"answer\":\"Synthetic recorded answer\",\"followUp\":\"Synthetic follow-up\"}");
        return Collections.unmodifiableMap(ids);
    }

    public static void seedEvent(org.springframework.jdbc.core.JdbcTemplate jdbc,long id,long plan,long revision,Long action,String type,Instant recorded,Instant occurred,String payload) {
        jdbc.update("INSERT INTO care_plan_event(id,patient_id,plan_id,revision_id,action_id,actor_id,actor_name,actor_role,actor_relation,entry_mode,event_type,note,occurred_at,recorded_at,payload_json) VALUES(?,1,?,?,?,8,'Synthetic historical helper','FAMILY','SPOUSE',?,?,'合成原文',?,?,?)",
                id,plan,revision,action,"RECEIPT_SUBMITTED".equals(type)?"ASSISTED":null,type,occurred==null?null:utc(occurred),utc(recorded),payload);
    }

    public static CareExecutionReport exampleReport(String language) {
        Instant generatedAt = Instant.parse("2026-10-04T12:00:00Z");
        CareExecutionReportContracts.Request request = CareExecutionReportContracts.parse(
                "{\"patientId\":1,\"timeZone\":\"UTC\",\"language\":\"" + language + "\"}", false, generatedAt);
        CareExecutionReport.Evidence restricted = new CareExecutionReport.Evidence(true, "MEDICAL_RECORD", 999L, "Synthetic hidden title", "/hidden");
        CareExecutionReport.Evidence readable = new CareExecutionReport.Evidence(false, "MEDICAL_RECORD", 100L, "Synthetic reference", "/medical-record?tab=list&patientId=1&recordId=100");
        CareExecutionReport.EventSummary receipt = new CareExecutionReport.EventSummary(201, 7, "RECEIPT_SUBMITTED", "Synthetic helper", "FAMILY", "SPOUSE", "ASSISTED",
                "合成代录备注，保留原文", null, Instant.parse("2026-09-06T09:00:00Z"), Instant.parse("2026-09-05T08:00:00Z"), Collections.singletonList(readable));
        CareExecutionReport.EventSummary help = new CareExecutionReport.EventSummary(202, 1, "HELP_REQUESTED", "Synthetic owner", "USER", "SELF", null,
                "Synthetic difficulty outside the activity window", null, Instant.parse("2026-09-01T09:00:00Z"), null, Collections.emptyList());
        CareExecutionReport.EventSummary followUp = new CareExecutionReport.EventSummary(203, 8, "FOLLOW_UP_RECORDED", "Synthetic nurse", "NURSE", "CARE_TEAM", null,
                "Synthetic administrative follow-up", "AWAITING_INFORMATION", Instant.parse("2026-09-02T09:00:00Z"), null, Collections.emptyList());
        List<CareExecutionReport.CurrentAction> actions = new ArrayList<>();
        String[] statuses = {"OPEN", "OPEN", "NEEDS_HELP", "SUBMITTED", "SUBMITTED", "CONFIRMED"};
        for (int i = 0; i < statuses.length; i++) {
            actions.add(new CareExecutionReport.CurrentAction(10, 20, 30 + i, 7, 1, "Synthetic published plan", "Synthetic original plan instructions",
                    "原文：按既有计划记录，不自动翻译", statuses[i], Instant.parse("2026-10-01T09:00:00Z"),
                    i == 3 || i == 4 ? receipt.getRecordedAt() : null, i == 0 || i == 2, i == 1, true,
                    i >= 3 ? receipt : null, null, null, i == 2 ? help : null, i == 2 ? followUp : null,
                    Collections.singletonList(i == 0 ? restricted : readable)));
        }
        Map<String, Long> counts = new LinkedHashMap<>(); counts.put("RECEIPT_SUBMITTED", 1L);
        CareExecutionReport.PeriodEvent event = new CareExecutionReport.PeriodEvent(receipt, 10, 20, 33L, 1, "Synthetic published plan",
                "Synthetic original plan instructions", "原文：按既有计划记录，不自动翻译", "SUBMITTED", "ACTIVE", true);
        CareExecutionReport.Question question = new CareExecutionReport.Question(401, "Synthetic visit question", "OPEN", "Synthetic discussion prompt", null, null,
                "Synthetic owner", 1L, "2026-09-01 08:30:00", null, null);
        return new CareExecutionReport(new CareExecutionReport.Patient(1, "Synthetic report patient"), new CareExecutionReport.Scope(null, "ALL_PLANS"),
                new CareExecutionReport.Metadata(request, generatedAt.minusSeconds(1), generatedAt), new CareExecutionReport.CurrentSummary(2, 1, 2, 1, 2, 1), actions,
                actions.subList(0, 5), new CareExecutionReport.ActivitySummary(1, 1, counts), Collections.singletonList(event), "AVAILABLE", Collections.singletonList(question));
    }
    private static java.time.LocalDateTime utc(Instant instant) { return java.time.LocalDateTime.ofInstant(instant,java.time.ZoneOffset.UTC); }

}
