package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.SysUser;
import org.familyhealthcare.service.SysUserRoleService;
import org.familyhealthcare.service.SysUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.familyhealthcare.controller.RoleController.isAdmin;

@RestController
@RequestMapping("/user")
@Api(tags = "User Management")
public class UserController {

    @Autowired
    private SysUserService userService;

    @Autowired
    private SysUserRoleService userRoleService;

    @GetMapping("/list")
    @ApiOperation("queryuserlist")
    public Result<List<SysUser>> list(HttpServletRequest request) {
        List<String> roleCodes = (List<String>) request.getAttribute("roleCodes");
        boolean isAdmin = roleCodes != null && roleCodes.contains("admin");
        if (isAdmin) {
            return Result.ok(userService.listUsers());
        }
        // non-managementmembercan onlyViewyourself information
        Long userId = (Long) request.getAttribute("userId");
        SysUser currentUser = userService.getUserById(userId);
        List<SysUser> list = new ArrayList<>();
        if (currentUser != null) {
            currentUser.setPassword(null);
            list.add(currentUser);
        }
        return Result.ok(list);
    }

    @PostMapping("/save")
    @ApiOperation("Adduser")
    public Result<String> save(@RequestBody SysUser user, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.error("You do not have permission to Createuser");
        }
        SysUser exist = userService.getUserByUsername(user.getUsername());
        if (exist != null) {
            return Result.error("Usernamealready storein ");
        }
        userService.createUser(user);
        return Result.ok("Created successfully");
    }

    @PutMapping("/update")
    @ApiOperation("updateuser")
    public Result<String> update(@RequestBody SysUser user, HttpServletRequest request) {
        if (!isAdmin(request)) {
            // non-managementmembercan onlyupdateyourself
            Long currentUserId = (Long) request.getAttribute("userId");
            if (user.getId() == null || !user.getId().equals(currentUserId)) {
                return Result.error("You do not have permission to EditOtheruserinformation");
            }
        }
        boolean success = userService.updateUser(user);
        return success ? Result.ok("Updated successfully") : Result.error("Update failed");
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("Deleteuser")
    public Result<String> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.error("You do not have permission to Deleteuser");
        }
        SysUser user = userService.getUserById(id);
        if (user != null && "admin".equals(user.getUsername())) {
            return Result.error("adminusercannot Delete");
        }
        boolean success = userService.deleteUser(id);
        return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
    }

    @PostMapping("/resetPassword")
    @ApiOperation("ResetPassword")
    public Result<String> resetPassword(@RequestBody Map<String, String> params, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.error("You do not have permission to ResetPassword");
        }
        Long id = Long.valueOf(params.get("id"));
        String newPassword = params.get("newPassword");
        boolean success = userService.resetPassword(id, newPassword);
        return success ? Result.ok("Password reset successfully") : Result.error("Password reset failed");
    }

    @PostMapping("/changePassword")
    @ApiOperation("EditCurrent AccountPassword")
    public Result<String> changePassword(@RequestBody Map<String, String> params, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) return Result.error(401, "Not signed in");
        try {
            boolean success = userService.changePassword(userId, params.get("currentPassword"), params.get("newPassword"));
            return success ? Result.ok("PasswordUpdated successfully, Please sign in again") : Result.error("Password update failed");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/assignRoles")
    @ApiOperation("for userassignRole")
    public Result<String> assignRoles(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.error("You do not have permission to assignRole");
        }
        Long userId = Long.valueOf(params.get("userId").toString());
        List<?> roleIdList = (List<?>) params.get("roleIds");
        List<Long> roleIds = new java.util.ArrayList<>();
        for (Object o : roleIdList) {
            roleIds.add(Long.valueOf(o.toString()));
        }
        userRoleService.assignRoles(userId, roleIds);
        return Result.ok("Role assigned successfully");
    }

    @GetMapping("/roles/{userId}")
    @ApiOperation("getuser RoleIDlist")
    public Result<List<Long>> getUserRoles(@PathVariable Long userId) {
        return Result.ok(userRoleService.getRoleIdsByUserId(userId));
    }

    @GetMapping("/detail/{id}")
    @ApiOperation("getuserDetails")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        SysUser user = userService.getUserById(id);
        if (user == null) {
            return Result.error("User not found");
        }
        user.setPassword(null);
        List<Long> roleIds = userRoleService.getRoleIdsByUserId(id);
        Map<String, Object> result = new HashMap<>();
        result.put("user", user);
        result.put("roleIds", roleIds);
        return Result.ok(result);
    }
}
