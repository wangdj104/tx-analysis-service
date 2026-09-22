package org.familyhealthcare.util;

import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/** Display-only labels for downloaded reports. Patient-entered content is never translated. */
public final class ExportLocalization {
    private ExportLocalization() { }

    public static String text(String english, String chinese) {
        return "zh".equals(LocaleContextHolder.getLocale().getLanguage()) ? chinese : english;
    }

    public static String label(String value) {
        if (value == null) return "";
        switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "BP": return text("Blood pressure", "血压");
            case "BG": return text("Blood glucose", "血糖");
            case "BOTH": case "BP_BG": return text("Blood pressure and glucose", "血压和血糖");
            case "FASTING": case "空腹": return text("Fasting", "空腹");
            case "AFTER MEAL2H": case "餐后2H": case "餐后2小时": return text("2 hours after a meal", "餐后2小时");
            case "BEFORE MEAL": case "餐前": return text("Before a meal", "餐前");
            case "RANDOM": case "随机": return text("Random", "随机");
            case "BEDTIME": case "睡前": return text("Bedtime", "睡前");
            case "GOOD": return text("Good", "良好");
            case "NORMAL": return text("Fair", "一般");
            case "POOR": return text("Poor", "较差");
            case "TOO_MUCH": return text("Excessive ultrafiltration", "超滤过多");
            case "INSUFFICIENT": return text("Insufficient ultrafiltration", "超滤不足");
            case "MATCH": return text("Ultrafiltration on target", "超滤达标");
            case "TAKEN": return text("Taken", "已服药");
            case "PENDING": return text("Pending", "待完成");
            case "MISSED": return text("Missed", "漏服");
            case "SKIPPED": return text("Skipped", "已跳过");
            case "SNOOZED": return text("Snoozed", "已延后");
            case "CANCELLED": return text("Cancelled", "已取消");
            case "MILD": return text("Mild", "轻度");
            case "MODERATE": return text("Moderate", "中度");
            case "SEVERE": return text("Severe", "重度");
            case "HYPOTENSION": return text("Hypotension", "低血压");
            case "HYPERTENSION": return text("Hypertension", "高血压");
            case "CRAMP": return text("Muscle cramp", "肌肉痉挛");
            case "NAUSEA": return text("Nausea", "恶心");
            case "HEADACHE": return text("Headache", "头痛");
            case "INFECTION": return text("Infection", "感染");
            case "OTHER": return text("Other", "其他");
            case "MONTH": return text("Month", "月");
            case "WEEK": return text("Week", "周");
            case "YEAR": return text("Year", "年");
            default: return value;
        }
    }
}
