package org.familyhealthcare.service;

import org.familyhealthcare.entity.SysMenu;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface SysMenuService extends IService<SysMenu> {

    List<SysMenu> getMenusByUserId(Long userId);

    List<SysMenu> getMenusByRoleId(Long roleId);

    List<SysMenu> getAllMenus();
}
