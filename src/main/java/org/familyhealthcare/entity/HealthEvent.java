package org.familyhealthcare.entity;
import com.baomidou.mybatisplus.annotation.*; import com.fasterxml.jackson.annotation.JsonFormat; import lombok.Data; import java.time.*;
@Data @TableName("health_event")
public class HealthEvent {
 @TableId(type=IdType.AUTO) private Long id; private Long userId; private Long patientId;
 @JsonFormat(pattern="yyyy-MM-dd") private LocalDate eventDate; private String eventTime; private String eventType; private String title;
 private String summary; private String sourceType; private Long sourceId; private String status; private String remark;
 private LocalDateTime createdAt; private LocalDateTime updatedAt;
}
