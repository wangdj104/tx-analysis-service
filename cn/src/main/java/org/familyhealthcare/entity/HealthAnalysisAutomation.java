package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("health_analysis_automation")
public class HealthAnalysisAutomation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long patientId;
    private String taskName;
    private Integer enabled;
    private String frequencyType;
    private Integer intervalDays;
    private Integer dayOfWeek;
    private Integer dayOfMonth;
    private String runTime;
    private Integer analysisRangeDays;
    private String analysisItems;
    private String notificationChannelIds;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime nextRunAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime lastRunAt;
    private String lastRunStatus;
    private String lastError;
    private Long lastAnalysisRecordId;
    @TableField(exist = false) private String lastAnalysisContent;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime updatedAt;
}
