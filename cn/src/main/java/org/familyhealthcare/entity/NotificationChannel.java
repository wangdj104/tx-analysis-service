package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("notification_channel")
public class NotificationChannel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String channelType;
    private String channelName;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String webhookUrl;
    @TableField(exist=false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String robotSecret;
    @TableField(exist=false)
    private String robotKeyword;
    @TableField(exist=false)
    private Boolean webhookConfigured;
    @TableField(exist=false)
    private Boolean robotSecretConfigured;
    private Integer enabled;
    private LocalDateTime lastTestAt;
    private String lastTestResult;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
