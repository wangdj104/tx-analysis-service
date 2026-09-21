package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.alibaba.fastjson2.JSON;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.*;

@Data @TableName("care_item")
public class CareItem {
    @TableId(type=IdType.AUTO) private Long id;
    private Long patientId;
    private Long userId;
    private String kind;
    private String title;
    private String status;
    @TableField(updateStrategy=FieldStrategy.IGNORED) private Long assignedUserId;
    private Long actorId;
    private String actorName;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") @TableField(updateStrategy=FieldStrategy.IGNORED) private LocalDateTime eventAt;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") @TableField(updateStrategy=FieldStrategy.IGNORED) private LocalDateTime notifyAt;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") @TableField(updateStrategy=FieldStrategy.IGNORED) private LocalDateTime notifiedAt;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") @TableField(updateStrategy=FieldStrategy.IGNORED) private LocalDateTime escalatedAt;
    @JsonIgnore private String dataJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Map<String,Object> getDetails() { return dataJson==null?new LinkedHashMap<>():JSON.parseObject(dataJson); }
    public void setDetails(Map<String,Object> value) { dataJson=JSON.toJSONString(value==null?Collections.emptyMap():value); }
}
