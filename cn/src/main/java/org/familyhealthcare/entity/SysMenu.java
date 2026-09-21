package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * systemMenu/Permissionentity
 */
@Data
@TableName("sys_menu")
public class SysMenu implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    private String menuName;

    /** Stable business identifier used for permission and navigation management. */
    private String menuCode;

    private String menuPath;

    private String menuIcon;

    private String permission;

    private Integer menuType;

    private Integer sortOrder;

    private Integer status;

    private LocalDateTime createTime;
}
