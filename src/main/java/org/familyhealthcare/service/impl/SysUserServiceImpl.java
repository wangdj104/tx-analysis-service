package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.SysUser;
import org.familyhealthcare.mapper.SysUserMapper;
import org.familyhealthcare.service.SysUserService;
import org.familyhealthcare.util.JwtUtil;
import org.familyhealthcare.util.PasswordPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysUserServiceImpl implements SysUserService {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private JwtUtil jwtUtil;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public String login(String username, String password) {
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUsername, username);
        SysUser user = sysUserMapper.selectOne(queryWrapper);

        if (user == null) {
            throw new RuntimeException("Username or Passworderror");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("Username or Passworderror");
        }

        if (user.getStatus() == 0) {
            throw new RuntimeException("The account is disabled.");
        }

        return jwtUtil.generateToken(user.getId(), user.getUsername());
    }

    @Override
    public SysUser getUserByUsername(String username) {
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUsername, username);
        return sysUserMapper.selectOne(queryWrapper);
    }

    @Override
    public void createUser(SysUser user) {
        PasswordPolicy.requireStrong(user.getPassword());
        String encodedPassword = passwordEncoder.encode(user.getPassword());
        user.setPassword(encodedPassword);
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        sysUserMapper.insert(user);
    }

    @Override
    public List<SysUser> listUsers() {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        qw.orderByDesc(SysUser::getCreateTime);
        return sysUserMapper.selectList(qw);
    }

    @Override
    public boolean updateUser(SysUser user) {
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            PasswordPolicy.requireStrong(user.getPassword());
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null);
        }
        return sysUserMapper.updateById(user) > 0;
    }

    @Override
    public boolean deleteUser(Long id) {
        return sysUserMapper.deleteById(id) > 0;
    }

    @Override
    public SysUser getUserById(Long id) {
        return sysUserMapper.selectById(id);
    }

    @Override
    public boolean resetPassword(Long id, String newPassword) {
        PasswordPolicy.requireStrong(newPassword);
        SysUser user = new SysUser();
        user.setId(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        return sysUserMapper.updateById(user) > 0;
    }

    @Override
    public boolean changePassword(Long id, String currentPassword, String newPassword) {
        SysUser existing = sysUserMapper.selectById(id);
        if (existing == null || !passwordEncoder.matches(currentPassword, existing.getPassword())) {
            throw new IllegalArgumentException("Current Passwordincorrect");
        }
        PasswordPolicy.requireStrong(newPassword);
        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(passwordEncoder.encode(newPassword));
        return sysUserMapper.updateById(update) > 0;
    }
}
