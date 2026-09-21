package org.familyhealthcare.config;

import org.familyhealthcare.entity.SysRole;
import org.familyhealthcare.entity.SysUser;
import org.familyhealthcare.entity.SysUserRole;
import org.familyhealthcare.mapper.SysRoleMapper;
import org.familyhealthcare.mapper.SysUserMapper;
import org.familyhealthcare.mapper.SysUserRoleMapper;
import org.familyhealthcare.service.SysUserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional first-run administrator bootstrap. Credentials only come from the
 * deployment environment and are never printed or stored in source control.
 */
@Component
@ConditionalOnProperty(name = "app.bootstrap-admin.enabled", havingValue = "true")
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserService userService;

    @Value("${app.bootstrap-admin.username:admin}")
    private String username;

    @Value("${app.bootstrap-admin.password:}")
    private String password;

    @Value("${app.bootstrap-admin.real-name:System Administrationmember}")
    private String realName;

    public BootstrapAdminInitializer(SysUserMapper userMapper, SysRoleMapper roleMapper,
                                     SysUserRoleMapper userRoleMapper, SysUserService userService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.userService = userService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD to fewneedneed 12 character");
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (user == null) {
            user = new SysUser();
            user.setUsername(username);
            user.setPassword(password);
            user.setRealName(realName);
            user.setStatus(1);
            userService.createUser(user);
        }

        SysRole adminRole = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, "admin"));
        if (adminRole == null) {
            throw new IllegalStateException("The database is not initialized: the administrator role is missing. Run init.sql.");
        }
        Long count = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, user.getId())
                .eq(SysUserRole::getRoleId, adminRole.getId()));
        if (count == 0) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(user.getId());
            relation.setRoleId(adminRole.getId());
            userRoleMapper.insert(relation);
        }
    }
}
