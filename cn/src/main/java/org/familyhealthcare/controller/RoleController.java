package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.SysMenu;
import org.familyhealthcare.entity.SysRole;
import org.familyhealthcare.service.SysMenuService;
import org.familyhealthcare.service.SysRoleService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/role")
@Api(tags = "Role Management")
public class RoleController {

    /**
     * determinecurrentrequestuserYesNofor managementmember
     */
    public static boolean isAdmin(HttpServletRequest request) {
        List<String> roleCodes = (List<String>) request.getAttribute("roleCodes");
        return roleCodes != null && roleCodes.contains("admin");
    }

    @Autowired
    private SysRoleService roleService;

    @Autowired
    private SysMenuService menuService;

    @GetMapping("/list")
    @ApiOperation("queryRolelist")
    public Result<List<SysRole>> list(HttpServletRequest request) {
        List<String> roleCodes = (List<String>) request.getAttribute("roleCodes");
        boolean isAdmin = roleCodes != null && roleCodes.contains("admin");
        if (isAdmin) {
            return Result.ok(roleService.list());
        }
        // non-managementmembercan onlyViewyourselfhavehas  Role
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(roleService.getRolesByUserId(userId));
    }

    @PostMapping("/save")
    @ApiOperation("Addor updateRole")
    public Result<String> save(@RequestBody SysRole role, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.error("You do not have permission to ActionsRole");
        }
        if ("admin".equals(role.getRoleCode())) {
            return Result.error("adminRolecannot Edit");
        }
        boolean success = roleService.saveOrUpdate(role);
        return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteRole")
    public Result<String> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.error("You do not have permission to DeleteRole");
        }
        SysRole role = roleService.getById(id);
        if (role != null && java.util.Arrays.asList("admin", "doctor", "patient", "family", "user").contains(role.getRoleCode())) {
            return Result.error("Built-in platform roles cannot be deleted");
        }
        boolean success = roleService.deleteRole(id);
        return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
    }

    @GetMapping("/menus/{roleId}")
    @ApiOperation("getRole MenuIDlist")
    public Result<List<Long>> getMenuIds(@PathVariable Long roleId) {
        return Result.ok(roleService.getMenuIdsByRoleId(roleId));
    }

    @PostMapping("/assignMenus")
    @ApiOperation("for RoleassignMenu")
    public Result<String> assignMenus(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.error("You do not have permission to assignMenu");
        }
        Long roleId = Long.valueOf(params.get("roleId").toString());
        SysRole role = roleService.getById(roleId);
        if (role != null && "admin".equals(role.getRoleCode())) {
            return Result.error("adminRole MenuPermissioncannot in pageEdit, for example needadjustPlease Manualrefreshdatabase");
        }
        List<?> menuIdList = (List<?>) params.get("menuIds");
        List<Long> menuIds = new java.util.ArrayList<>();
        for (Object o : menuIdList) {
            menuIds.add(Long.valueOf(o.toString()));
        }
        List<SysMenu> selectedMenus = menuIds.isEmpty() ? java.util.Collections.emptyList() : menuService.listByIds(menuIds);
        boolean containsAdministration = selectedMenus.stream().anyMatch(menu ->
                java.util.Arrays.asList("system", "system-user", "system-role", "system-menu", "system-audit", "platform-branding").contains(menu.getMenuCode())
                        || java.util.Arrays.asList("user:manage", "role:manage", "menu:manage", "audit:view", "branding:manage").contains(menu.getPermission())
                        || (menu.getMenuPath() != null && menu.getMenuPath().startsWith("/system/") && !"/system/patient".equals(menu.getMenuPath())));
        if (containsAdministration) {
            return Result.error(400, "Platform administration menus can only belong to the administrator role");
        }
        roleService.assignMenus(roleId, menuIds);
        return Result.ok("Menuassignsuccessful");
    }

    @GetMapping("/detail/{id}")
    @ApiOperation("getRoleDetails")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        SysRole role = roleService.getById(id);
        if (role == null) {
            return Result.error("Role not found");
        }
        List<Long> menuIds = roleService.getMenuIdsByRoleId(id);
        List<SysMenu> menus = menuService.getMenusByRoleId(id);
        Map<String, Object> result = new HashMap<>();
        result.put("role", role);
        result.put("menuIds", menuIds);
        result.put("menus", menus);
        return Result.ok(result);
    }
}
