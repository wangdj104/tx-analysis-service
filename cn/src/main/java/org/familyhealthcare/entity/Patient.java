package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("patient")
public class Patient implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String gender;

    private LocalDate birthDate;

    private String idCard;

    private String phone;

    private String address;

    private String emergencyContact;

    private String emergencyPhone;

    private String medicalHistory;

    private String remark;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    private Long userId;

    @TableField(exist = false)
    private List<Long> specialtyRoleIds;
}
