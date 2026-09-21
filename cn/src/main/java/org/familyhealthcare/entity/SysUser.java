package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * systemuserentity
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * userID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Username (Sign InAccount)
     */
    private String username;

    /**
     * Password (BCryptaddsecret)
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /**
     * realName
     */
    private String realName;

    /**
     * Phone Number
     */
    private String phone;

    /**
     * email
     */
    private String email;

    /**
     * Status: 0-Disabled, 1-Enabled
     */
    private Integer status;

    /**
     * Created At
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * Updated At
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * logicDelete: 0-not Delete, 1-Deleted
     */
    @TableLogic
    private Integer deleted;
}
