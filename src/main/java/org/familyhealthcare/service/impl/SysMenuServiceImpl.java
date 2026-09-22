package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.SysMenu;
import org.familyhealthcare.mapper.SysMenuMapper;
import org.familyhealthcare.mapper.SysRoleMapper;
import org.familyhealthcare.service.SysMenuService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {
    private static final Set<String> ADMIN_ONLY_PERMISSIONS = new HashSet<>(Arrays.asList(
            "user:manage", "role:manage", "menu:manage", "audit:view", "branding:manage"
    ));
    private static final Set<String> ADMIN_ONLY_CODES = new HashSet<>(Arrays.asList(
            "system", "system-user", "system-role", "system-menu", "system-audit", "platform-branding"
    ));
    @Autowired private SysRoleMapper roleMapper;

    @Override
    public List<SysMenu> getMenusByUserId(Long userId) {
        List<SysMenu> menus = baseMapper.selectMenusByUserId(userId);
        boolean admin = roleMapper.selectRolesByUserId(userId).stream().anyMatch(role -> "admin".equals(role.getRoleCode()));
        if (admin) return menus;
        return menus.stream().filter(menu -> !ADMIN_ONLY_PERMISSIONS.contains(menu.getPermission())
                && !ADMIN_ONLY_CODES.contains(menu.getMenuCode())
                && (menu.getMenuPath() == null || !menu.getMenuPath().startsWith("/system/") || "/system/patient".equals(menu.getMenuPath())))
                .collect(Collectors.toList());
    }

    @Override
    public List<SysMenu> getMenusByRoleId(Long roleId) {
        return baseMapper.selectMenusByRoleId(roleId);
    }

    @Override
    public List<SysMenu> getAllMenus() {
        return baseMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getStatus, 1)
                .orderByAsc(SysMenu::getSortOrder));
    }
}
