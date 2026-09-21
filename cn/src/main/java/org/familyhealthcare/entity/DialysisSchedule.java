package org.familyhealthcare.entity;
import com.baomidou.mybatisplus.annotation.*; import com.fasterxml.jackson.annotation.JsonFormat; import lombok.Data; import java.time.*;
@Data @TableName("dialysis_schedule")
public class DialysisSchedule {
 @TableId(type=IdType.AUTO) private Long id; private Long userId; private Long patientId;
 @JsonFormat(pattern="yyyy-MM-dd") private LocalDate scheduleDate; private String scheduleTime; private String status;
 private Long completedRecordId; private String remark; private LocalDateTime createdAt; private LocalDateTime updatedAt;
}
