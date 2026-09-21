package org.familyhealthcare.service;

import org.familyhealthcare.entity.SysUser;

import java.util.List;

/**
 * userservice interface
 */
public interface SysUserService {

    String login(String username, String password);

    SysUser getUserByUsername(String username);

    void createUser(SysUser user);

    List<SysUser> listUsers();

    boolean updateUser(SysUser user);

    boolean deleteUser(Long id);

    SysUser getUserById(Long id);

    boolean resetPassword(Long id, String newPassword);

    boolean changePassword(Long id, String currentPassword, String newPassword);
}
