package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationMessageLocalizerTest {
    private final NotificationMessageLocalizer localizer = new NotificationMessageLocalizer();

    @Test void followUpReschedulingUsesItsOwnEventAndPreservesTheAppointmentTime() {
        String content="The periodic follow-up at 2026-09-24T09:30 could not be booked because the clinician or time slot is unavailable. Please choose another appointment time.";
        NotificationMessageLocalizer.Message chinese=localizer.localize("APPOINTMENT_RESCHEDULE_REQUIRED","Follow-up needs rescheduling",content,"zh-CN");
        assertEquals("复诊需要重新预约",chinese.getTitle());
        assertEquals("原定 2026-09-24T09:30 的周期复诊因医生或预约时段不可用而未能预约，请选择其他就诊时间。",chinese.getContent());
        assertEquals(content,localizer.localize("APPOINTMENT_RESCHEDULE_REQUIRED","Follow-up needs rescheduling",content,"en-US").getContent());
    }

    @Test void structuredMeasurementAndRehabNotificationsUseLocalizedLabelsWithoutChangingFreeText() {
        String measurement="GLUCOSE recorded at 2026-09-22T09:30 crossed the configured safety range. Please review and confirm the next action.";
        assertEquals("血糖 于 2026-09-22T09:30 的记录超出已配置的安全范围，请复核并确认下一步处理。",localizer.localize("MEASUREMENT_ALERT","",measurement,"zh-CN").getContent());
        assertEquals("Blood glucose recorded at 2026-09-22T09:30 crossed the configured safety range. Please review and confirm the next action.",localizer.localize("MEASUREMENT_ALERT","",measurement,"en-US").getContent());
        String rehabilitation="WOUND was marked abnormal. Review the structured observation and contact the patient if needed.";
        assertEquals("伤口记录被标记为异常，请复核结构化记录，并在需要时联系患者。",localizer.localize("REHAB_ALERT","",rehabilitation,"zh-CN").getContent());
        assertEquals("Wound record was marked abnormal. Review the structured observation and contact the patient if needed.",localizer.localize("REHAB_ALERT","",rehabilitation,"en-US").getContent());
        for(String event:new String[]{"MEASUREMENT_ALERT","REHAB_ALERT","MENTAL_ASSESSMENT"}) {
            String note="Patient wrote: GLUCOSE and HIGH were mentioned; do not alter this note.";
            assertEquals(note,localizer.localize(event,"",note,"zh-CN").getContent());
        }
    }

    @Test void mentalAssessmentSeverityIsTranslatedOnlyInsideTheKnownSystemTemplate() {
        String body="PHQ9 result: MODERATELY_SEVERE. Review privately in the clinician workspace.";
        assertEquals("PHQ-9 结果：中重度。请在医生工作台中私密复核。",localizer.localize("MENTAL_ASSESSMENT","",body,"zh-CN").getContent());
        assertEquals("PHQ-9 result: Moderately severe. Review privately in the clinician workspace.",localizer.localize("MENTAL_ASSESSMENT","",body,"en-US").getContent());
        assertEquals("WHO-5 结果：建议进一步评估。请在医生工作台中私密复核。",localizer.localize("MENTAL_ASSESSMENT","","WHO5 result: REVIEW_REQUIRED. Review privately in the clinician workspace.","zh-CN").getContent());
        String freeText="Clinician note: PHQ9 result: HIGH; patient reports improvement.";
        assertEquals(freeText,localizer.localize("MENTAL_ASSESSMENT","",freeText,"zh-CN").getContent());
    }
    @Test void localizesOnlyTheKnownFollowUpConflictTemplate() {
        String content = "The periodic follow-up at 2026-09-23T09:00 could not be booked because the clinician or time slot is unavailable. Please choose another appointment time.";
        assertEquals("原定 2026-09-23T09:00 的周期复诊因医生或预约时段不可用而未能预约，请选择其他就诊时间。", localizer.localize("CARE_FOLLOW_UP", "Follow-up", content, "zh-CN").getContent());
        assertEquals("Clinical detail unchanged", localizer.localize("CARE_FOLLOW_UP", "Follow-up", "Clinical detail unchanged", "zh-CN").getContent());
        assertEquals(content, localizer.localize("CARE_FOLLOW_UP", "Follow-up", content, "en-US").getContent());
    }

    @Test void keepsEnglishForEnglishRecipients() {
        NotificationMessageLocalizer.Message message = localizer.localize(
                "EMERGENCY", "Emergency call from bound patient", "Open the emergency event.", "en-US");
        assertEquals("Emergency call from bound patient", message.getTitle());
        assertEquals("Open the emergency event.", message.getContent());
    }

    @Test void localizesEmergencyForChineseRecipients() {
        NotificationMessageLocalizer.Message message = localizer.localize(
                "EMERGENCY", "Emergency call from bound patient", "Open the emergency event.", "zh-CN");
        assertEquals("绑定患者发起紧急呼救", message.getTitle());
        assertEquals("请打开紧急事件查看定位、既往病史、过敏史和当前用药。", message.getContent());
    }

    @Test void preservesDynamicValuesWhenLocalizingMedication() {
        NotificationMessageLocalizer.Message message = localizer.localize(
                "MEDICATION_REMINDER", "Medication Reminders", "阿司匹林 · 08:00 · As prescribed", "zh-CN");
        assertEquals("用药提醒", message.getTitle());
        assertEquals("阿司匹林 · 08:00 · 遵医嘱", message.getContent());
    }
}
