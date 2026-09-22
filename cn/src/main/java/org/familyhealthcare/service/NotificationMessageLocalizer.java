package org.familyhealthcare.service;

import org.springframework.stereotype.Service;

/** Localizes outbound webhook notifications while retaining dynamic clinical values. */
@Service
public class NotificationMessageLocalizer {
    public Message localize(String eventType, String title, String content, String language) {
        if (language == null || !language.toLowerCase(java.util.Locale.ROOT).startsWith("zh")) return new Message(title, clinicalTemplate(eventType,content,false));
        String type = eventType == null ? "" : eventType;
        String zhTitle = chineseTitle(type, title);
        String zhContent = chineseContent(type, content);
        return new Message(zhTitle, zhContent);
    }

    private String chineseTitle(String type, String title) {
        switch (type) {
            case "MEASUREMENT_ALERT": return "健康指标异常提醒";
            case "APPOINTMENT_UPDATED": return "预约已更新";
            case "APPOINTMENT_CANCELLED": return "预约已取消";
            case "APPOINTMENT_REMINDER": return "预约提醒";
            case "APPOINTMENT_RESCHEDULE_REQUIRED": return "复诊需要重新预约";
            case "VISIT_SUMMARY": return "诊后小结已发布";
            case "PRESCRIPTION_UPDATED": return "用药医嘱已更新";
            case "TREATMENT_PLAN": return "治疗计划已更新";
            case "REHAB_ALERT": return "康复异常待复核";
            case "EMERGENCY": return "绑定患者发起紧急呼救";
            case "MENTAL_ASSESSMENT": return "心理量表结果待复核";
            case "MENTAL_ASSESSMENT_DUE": return "心理自评待完成";
            case "VACCINATION_REMINDER": return "疫苗接种提醒";
            case "INDICATOR_ALERT": return suffix(title, " Health Alerts", "健康预警");
            case "FAMILY_CARE_REMINDER": return "家庭照护提醒";
            case "CARE_FOLLOW_UP": return "需要跟进";
            case "MEDICATION_RESTOCK": return "药品补货提醒";
            case "MEDICATION_MISSED": return "用药尚未确认";
            case "MEDICATION_REMINDER": return "用药提醒";
            case "HEALTH_ANALYSIS_DRAFT": return replacePrefix(title, "Health analysis draft ready · ", "健康分析草稿待审核 · ");
            case "HEALTH_ANALYSIS_REVIEWED": return replacePrefix(title, "Reviewed health analysis · ", "已审核健康分析 · ");
            case "CHANNEL_TEST": return "澄心健康通知测试";
            default: return title;
        }
    }

    private String chineseContent(String type, String content) {
        if (content == null) return null;
        switch (type) {
            case "MEASUREMENT_ALERT":
                return clinicalTemplate(type,content,true);
            case "APPOINTMENT_UPDATED":
                return content.replace("Appointment scheduled for ", "预约时间为 ").replace(". The shared care calendar has been updated.", "，共享照护日程已同步更新。");
            case "APPOINTMENT_CANCELLED":
                return "The shared appointment was cancelled.".equals(content) ? "共享预约已取消。" : content;
            case "APPOINTMENT_REMINDER":
                return content.replace("Upcoming ", "即将开始的 ").replace(" appointment at ", " 问诊时间：").replace(". Open the shared schedule for details.", "。请打开共享日程查看详情。");
            case "APPOINTMENT_RESCHEDULE_REQUIRED":
                return content.replaceFirst("^The periodic follow-up at (.+) could not be booked because the clinician or time slot is unavailable\\. Please choose another appointment time\\.$", "原定 $1 的周期复诊因医生或预约时段不可用而未能预约，请选择其他就诊时间。");
            case "VISIT_SUMMARY": return "医生已发布诊后小结和后续医嘱，请及时查看。";
            case "PRESCRIPTION_UPDATED":
                return content.replace("A clinician published prescription version ", "医生已发布处方版本 ").replace(". Review the new dose and frequency before the next intake.", "。下次服药前请核对新的剂量和频次。");
            case "TREATMENT_PLAN": return "医生新增或调整了治疗与康复计划，请及时查看。";
            case "REHAB_ALERT":
                return clinicalTemplate(type,content,true);
            case "EMERGENCY": return "请打开紧急事件查看定位、既往病史、过敏史和当前用药。";
            case "MENTAL_ASSESSMENT":
                return clinicalTemplate(type,content,true);
            case "MENTAL_ASSESSMENT_DUE":
                return content.replace(" is ready. Results follow your selected privacy setting.", " 已可填写，结果将遵循你选择的隐私设置。");
            case "VACCINATION_REMINDER":
                return content.replace(" dose ", " 第 ").replace(" is planned for ", " 剂计划日期：").replace(". This is a manually maintained plan; confirm with the vaccination provider.", "。该计划为手工维护，请向接种机构确认。");
            case "INDICATOR_ALERT": return "新的检查结果超出已配置阈值，请在关注中心复核；任何临床处置仍需医生确认。";
            case "MEDICATION_RESTOCK": return content.replaceFirst(" — (.+) remaining$", " — 剩余 $1");
            case "MEDICATION_REMINDER": return content.replace("As prescribed", "遵医嘱");
            case "CARE_FOLLOW_UP": return content.replaceFirst("^The periodic follow-up at (.+) could not be booked because the clinician or time slot is unavailable\\. Please choose another appointment time\\.$", "原定 $1 的周期复诊因医生或预约时段不可用而未能预约，请选择其他就诊时间。");
            case "HEALTH_ANALYSIS_DRAFT": return "自动健康分析草稿已生成，尚未向患者发送临床建议；请在医生工作台中审核或驳回。";
            case "HEALTH_ANALYSIS_REVIEWED": return "The reviewed analysis is available in Chengxin Health.".equals(content) ? "已审核的健康分析可在澄心健康中查看。" : content;
            case "CHANNEL_TEST": return "这是一条测试消息，通知渠道已连接成功。";
            default: return content;
        }
    }

    /** Translate only complete, known system templates; clinical notes and unknown content stay verbatim. */
    private String clinicalTemplate(String type,String content,boolean chinese) {
        if(content==null||type==null)return content;
        if("MEASUREMENT_ALERT".equals(type)) {
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("^(BP|GLUCOSE|SPO2|WEIGHT|HEART_RATE|TEMPERATURE|CUSTOM) recorded at ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+) crossed the configured safety range\\. Please review and confirm the next action\\.$").matcher(content);
            if(match.matches())return label(match.group(1),chinese)+(chinese?" 于 ":" recorded at ")+match.group(2)+(chinese?" 的记录超出已配置的安全范围，请复核并确认下一步处理。":" crossed the configured safety range. Please review and confirm the next action.");
        } else if("REHAB_ALERT".equals(type)) {
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("^(EXERCISE|WOUND|DRAIN|SYMPTOM) was marked abnormal\\. Review the structured observation and contact the patient if needed\\.$").matcher(content);
            if(match.matches())return label(match.group(1),chinese)+(chinese?"被标记为异常，请复核结构化记录，并在需要时联系患者。":" was marked abnormal. Review the structured observation and contact the patient if needed.");
        } else if("MENTAL_ASSESSMENT".equals(type)) {
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("^(PHQ-?9|GAD-?7|WHO-?5) result: (MINIMAL|MILD|MODERATE|MODERATELY_SEVERE|SEVERE|REVIEW_REQUIRED|HIGH)\\. Review privately in the clinician workspace\\.$").matcher(content);
            if(match.matches()) {
                String scale=match.group(1).replace("-","").replace("PHQ9","PHQ-9").replace("GAD7","GAD-7").replace("WHO5","WHO-5");
                String severity="WHO-5".equals(scale)&&"MINIMAL".equals(match.group(2))?(chinese?"未达评估提醒阈值":"Below screening alert threshold"):label(match.group(2),chinese);
                return scale+(chinese?" 结果：":" result: ")+severity+(chinese?"。请在医生工作台中私密复核。":". Review privately in the clinician workspace.");
            }
        }
        return content;
    }

    private String label(String value,boolean chinese) {
        switch(value) {
            case "BP":return chinese?"血压":"Blood pressure";
            case "GLUCOSE":return chinese?"血糖":"Blood glucose";
            case "SPO2":return chinese?"血氧":"Blood oxygen";
            case "WEIGHT":return chinese?"体重":"Weight";
            case "HEART_RATE":return chinese?"心率":"Heart rate";
            case "TEMPERATURE":return chinese?"体温":"Temperature";
            case "CUSTOM":return chinese?"自定义指标":"Custom measurement";
            case "EXERCISE":return chinese?"康复训练":"Rehabilitation exercise";
            case "WOUND":return chinese?"伤口记录":"Wound record";
            case "DRAIN":return chinese?"引流记录":"Drainage record";
            case "SYMPTOM":return chinese?"症状记录":"Symptom record";
            case "MINIMAL":return chinese?"极轻微":"Minimal";
            case "MILD":return chinese?"轻度":"Mild";
            case "MODERATE":return chinese?"中度":"Moderate";
            case "MODERATELY_SEVERE":return chinese?"中重度":"Moderately severe";
            case "SEVERE":return chinese?"重度":"Severe";
            case "HIGH":return chinese?"较高":"High";
            case "REVIEW_REQUIRED":return chinese?"建议进一步评估":"Further assessment suggested";
            default:return value;
        }
    }

    private String replacePrefix(String value, String english, String chinese) {
        return value != null && value.startsWith(english) ? chinese + value.substring(english.length()) : value;
    }

    private String suffix(String value, String english, String chinese) {
        return value != null && value.endsWith(english) ? value.substring(0, value.length() - english.length()) + chinese : value;
    }

    public static final class Message {
        private final String title;
        private final String content;
        public Message(String title, String content) { this.title = title; this.content = content; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
    }
}
