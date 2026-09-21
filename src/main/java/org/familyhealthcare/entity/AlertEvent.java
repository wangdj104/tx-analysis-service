package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("alert_event")
public class AlertEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long alertId;
    private Long patientId;
    private Long actorId;
    private String actorName;
    private String fromStatus;
    private String toStatus;
    private String note;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
