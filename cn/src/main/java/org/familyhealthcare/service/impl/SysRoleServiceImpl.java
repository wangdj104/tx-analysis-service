package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.SysRole;
import org.familyhealthcare.entity.SysRoleMenu;
import org.familyhealthcare.mapper.SysRoleMapper;
import org.familyhealthcare.mapper.SysRoleMenuMapper;
import org.familyhealthcare.mapper.SysUserRoleMapper;
import org.familyhealthcare.service.SysRoleService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    @Autowired
    private SysRoleMenuMapper roleMenuMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Override
    public List<SysRole> getRolesByUserId(Long userId) {
        return baseMapper.selectRolesByUserId(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignMenus(Long roleId, List<Long> menuIds) {
        // firstDeletethis Roleoriginalhas  Menurelated
        roleMenuMapper.deleteByRoleId(roleId);
        // againinsertenternew Menurelated
        if (menuIds != null && !menuIds.isEmpty()) {
            List<SysRoleMenu> list = new ArrayList<>();
            for (Long menuId : menuIds) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setRoleId(roleId);
                rm.setMenuId(menuId);
                list.add(rm);
            }
            for (SysRoleMenu rm : list) {
                roleMenuMapper.insert(rm);
            }
        }
        return true;
    }

    @Override
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        List<SysRoleMenu> list = roleMenuMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRoleMenu>()
                        .eq(SysRoleMenu::getRoleId, roleId));
        return list.stream().map(SysRoleMenu::getMenuId).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRole(Long roleId) {
        roleMenuMapper.deleteByRoleId(roleId);
        userRoleMapper.deleteByRoleId(roleId);
        return removeById(roleId);
    }
}
