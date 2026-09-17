package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Medication Remindersplanentity
 */
@Data
@TableName("medication_reminder")
@ApiModel("Medication Remindersplan")
public class MedicationReminder {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("userID")
    private Long userId;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("relatedMedicationID")
    private Long medicationId;

    @ApiModelProperty("ReminderTime HH:mm")
    private String remindTime;

    @ApiModelProperty("weekseveral, 1=weekone, comma-separated, for example 1,2,3,4,5")
    private String repeatDays;

    @ApiModelProperty("Dose")
    private String dosage;

    @ApiModelProperty("YesNoEnabled: 0-Disabled, 1-Enabled")
    private Integer enabled;

    @ApiModelProperty("up timestriggerTime")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastTriggerAt;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // non-datadatabasefield
    @TableField(exist = false)
    @ApiModelProperty("relatedMedicationinformation")
    private Medication medication;
}
