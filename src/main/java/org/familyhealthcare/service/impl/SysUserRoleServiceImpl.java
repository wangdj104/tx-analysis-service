package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.SysUserRole;
import org.familyhealthcare.mapper.SysUserRoleMapper;
import org.familyhealthcare.service.SysUserRoleService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysUserRoleServiceImpl extends ServiceImpl<SysUserRoleMapper, SysUserRole> implements SysUserRoleService {
    @Autowired private JdbcTemplate jdbc;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        if (roleIds != null && !roleIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
            Integer specialtyCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_role WHERE id IN (" + placeholders + ") AND LEFT(role_code,10)='specialty_'",
                    Integer.class, roleIds.toArray());
            if (specialtyCount != null && specialtyCount > 0)
                throw new IllegalArgumentException("Specialty roles belong to patient records, not user accounts.");
        }
        baseMapper.deleteByUserId(userId);
        if (roleIds != null && !roleIds.isEmpty()) {
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                baseMapper.insert(ur);
            }
        }
    }

    @Override
    public List<Long> getRoleIdsByUserId(Long userId) {
        List<SysUserRole> list = baseMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId));
        return list.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
    }
}
