package org.familyhealthcare.entity;
import com.baomidou.mybatisplus.annotation.*;import lombok.Data;import java.time.LocalDateTime;
@Data @TableName("operation_audit_log")
public class OperationAuditLog {@TableId(type=IdType.AUTO)private Long id;private Long userId;private String username;private String requestMethod;private String requestPath;private Integer statusCode;private Long durationMs;private String clientIp;private String actionType;private String targetType;private String targetId;private String detailJson;private LocalDateTime createdAt;}
