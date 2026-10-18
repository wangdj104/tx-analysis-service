package org.familyhealthcare.entity;
import com.baomidou.mybatisplus.annotation.*; import com.fasterxml.jackson.annotation.JsonFormat; import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("medication_intake")
public class MedicationIntake {
 @TableField(exist=false) private String drugName;
 @TableId(type=IdType.AUTO) private Long id; private Long userId; private Long patientId; private Long reminderId; private Long medicationId;
 @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime scheduledAt; private String status;
 @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime actionAt;
 @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime snoozeUntil;
 private String dosage; private String reason; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    // Read-only server projection, populated only when the additive schema is ready.
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private Integer workflowVersion;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private Long executionId;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private Long reconciliationId;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private String provenance;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private String followUpState;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private String followUpKind;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private Integer followUpCycle;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private java.time.Instant scheduledAtUtc;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private java.time.Instant actualAtUtc;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) private String nextAction;
}
