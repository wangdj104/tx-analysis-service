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
}
