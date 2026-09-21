package org.familyhealthcare.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * healthmonitoringcenteronetimesRefreshneed completesnapshot.
 */
@Data
public class MonitoringSnapshotVO {

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime generatedAt;
    private PatientSummary patient;
    private String overallStatus;
    private String statusLabel;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastDataAt;
    private Map<String, Object> latestVitals = new LinkedHashMap<>();
    private Metrics metrics = new Metrics();
    private List<Signal> signals = new ArrayList<>();
    private List<VitalPoint> vitalTrend = new ArrayList<>();
    private List<AlertItem> activeAlerts = new ArrayList<>();
    private List<TaskItem> todayTasks = new ArrayList<>();
    private List<EventItem> recentEvents = new ArrayList<>();
    private List<String> careSuggestions = new ArrayList<>();

    @Data
    public static class PatientSummary {
        private Long id;
        private String name;
        private String gender;
        private Integer age;
        private String emergencyContact;
        private String emergencyPhone;
    }

    @Data
    public static class Metrics {
        private Integer activeAlertCount = 0;
        private Integer criticalAlertCount = 0;
        private Integer todayTaskCount = 0;
        private Integer completedTaskCount = 0;
        private BigDecimal adherenceRate = BigDecimal.ZERO;
        private Integer dataCompleteness = 0;
    }

    @Data
    public static class Signal {
        private String key;
        private String label;
        private String status;
        private String statusLabel;
        private String value;
        private String unit;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime updatedAt;
        private String freshnessText;
    }

    @Data
    public static class VitalPoint {
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate date;
        private String time;
        private Integer systolic;
        private Integer diastolic;
        private BigDecimal glucose;
        private String glucoseUnit;
        private String measurePeriod;
        private Boolean abnormal;
    }

    @Data
    public static class AlertItem {
        private Long id;
        private String type;
        private String level;
        private String title;
        private String value;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime triggeredAt;
        private String status;
        private String handlingNote;
    }

    @Data
    public static class TaskItem {
        private Long id;
        private String taskType;
        private String title;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime scheduledAt;
        private String status;
        private String dosage;
        private String description;
    }

    @Data
    public static class EventItem {
        private Long id;
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate date;
        private String time;
        private String type;
        private String title;
        private String summary;
        private String sourceType;
        private String status;
    }
}
